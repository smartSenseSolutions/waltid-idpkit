package id.walt.idp.oidc

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.interfaces.DecodedJWT
import com.google.common.cache.CacheBuilder
import com.jayway.jsonpath.JsonPath
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.oauth2.sdk.*
import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.oauth2.sdk.token.*
import com.nimbusds.openid.connect.sdk.*
import com.nimbusds.openid.connect.sdk.claims.UserInfo
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import com.nimbusds.openid.connect.sdk.token.OIDCTokens
import id.walt.WALTID_DATA_ROOT
import id.walt.crypto.KeyAlgorithm
import id.walt.crypto.KeyId
import id.walt.idp.IDPManager
import id.walt.idp.IDPType
import id.walt.idp.config.IDPClient
import id.walt.idp.util.WaltIdAlgorithm
import id.walt.idp.config.IDPConfig
import id.walt.idp.siop.SIOPState
import id.walt.model.dif.*
import id.walt.model.oidc.VpTokenClaim
import id.walt.services.hkvstore.FileSystemHKVStore
import id.walt.services.hkvstore.FilesystemStoreConfig
import id.walt.services.key.KeyFormat
import id.walt.services.key.KeyService
import id.walt.services.keystore.HKVKeyStoreService
import id.walt.services.keystore.KeyType
import id.walt.services.oidc.OIDCUtils
import id.walt.services.vcstore.HKVVcStoreService
import id.walt.verifier.backend.SIOPResponseVerificationResult
import id.walt.verifier.backend.VerifierConfig
import id.walt.verifier.backend.VerifierManager
import id.walt.webwallet.backend.context.UserContext
import id.walt.webwallet.backend.context.WalletContextManager
import io.javalin.http.BadRequestResponse
import io.javalin.http.ForbiddenResponse
import io.javalin.http.InternalServerErrorResponse
import javalinjwt.JWTProvider
import mu.KotlinLogging
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

object OIDCManager : IDPManager {
  val EXPIRATION_TIME: Duration = Duration.ofMinutes(5)
  val sessionCache = CacheBuilder.newBuilder().expireAfterAccess(EXPIRATION_TIME.seconds, TimeUnit.SECONDS).build<String, OIDCSession>()
  val log = KotlinLogging.logger {}

  val oidcContext = UserContext(
    contextId = "OIDCContext",
    hkvStore = FileSystemHKVStore(FilesystemStoreConfig("$WALTID_DATA_ROOT/data/idp")),
    keyStore = HKVKeyStoreService(),
    vcStore = HKVVcStoreService()
  )

  lateinit var keyId: KeyId
  lateinit var jwtAlgorithm: Algorithm
  lateinit var keySet: JWKSet

  init {
    WalletContextManager.runWith(oidcContext) {
      if(!IDPConfig.config.keyId.isNullOrEmpty()) {
        keyId = KeyId(IDPConfig.config.keyId)
      } else {
        keyId = KeyService.getService().listKeys().map { k -> k.keyId }.firstOrNull()
          ?: KeyService.getService().generate(KeyAlgorithm.RSA)
      }
      val key = KeyService.getService().load(keyId.id)
      keySet = JWKSet(JWK.parse(KeyService.getService().export(keyId.id, KeyFormat.JWK, KeyType.PUBLIC)))
      jwtAlgorithm = WaltIdAlgorithm(keyId, oidcContext, key.algorithm)
      log.info("Using IDP key: {}", keyId)
    }
  }

  val oidcProviderMetadata get() = OIDCProviderMetadata(
    Issuer(oidcApiUrl),
    listOf(SubjectType.PUBLIC),
    // TODO: provide this endpoint !!
    URI.create("$oidcApiUrl/jwkSet")
  ).apply {
    authorizationEndpointURI = URI.create("$oidcApiUrl/authorize")
    pushedAuthorizationRequestEndpointURI = URI.create("$oidcApiUrl/par")
    tokenEndpointURI = URI.create("$oidcApiUrl/token")
    userInfoEndpointURI = URI.create("$oidcApiUrl/userInfo")
    grantTypes = listOf(GrantType.AUTHORIZATION_CODE)
    responseTypes = listOf(ResponseType.CODE, ResponseType.IDTOKEN, ResponseType.TOKEN, ResponseType.CODE_IDTOKEN, ResponseType.CODE_TOKEN, ResponseType.IDTOKEN_TOKEN, ResponseType.CODE_IDTOKEN_TOKEN)
    claims = listOf("vp_token", *(IDPConfig.config.claimMappings?.mappings?.map { m -> m.claim }?.toSet() ?: setOf()).toTypedArray())
    scopes = Scope("openid", *(IDPConfig.config.claimMappings?.mappings?.flatMap { m -> m.scope }?.toSet() ?: setOf()).toTypedArray())
    setCustomParameter("wallets_supported", VerifierConfig.config.wallets.values.map { wallet ->
      mapOf(
        "id" to wallet.id,
        "description" to wallet.description
      )
    })
  }

  fun generateVpTokenClaim(authRequest: AuthorizationRequest): VpTokenClaim {
    return OIDCUtils.getVCClaims(authRequest).vp_token ?:
      VpTokenClaim(PresentationDefinition(
        id = "1",
        input_descriptors = authRequest.scope.flatMap { s -> IDPConfig.config.claimMappings?.credentialTypesForScope(s) ?: setOf() }.toSet().mapIndexed {
          index, s -> InputDescriptor(index.toString(), constraints = InputDescriptorConstraints(
            fields = listOf(InputDescriptorField(listOf("$.type"), "1", null, mapOf("const" to s)))
          ), group = setOf("A"))
        }, submission_requirements = listOf(SubmissionRequirement(SubmissionRequirementRule.all, from = "A"))
      ))
  }

  fun initOIDCSession(authRequest: AuthorizationRequest): OIDCSession {
    val vpTokenClaim = generateVpTokenClaim(authRequest)
    val walletId = authRequest.customParameters["walletId"]?.firstOrNull() ?: VerifierConfig.config.wallets.values.map { wc -> wc.id }.firstOrNull() ?: throw InternalServerErrorResponse("Known wallets not configured")
    val wallet = VerifierConfig.config.wallets[walletId] ?: throw BadRequestResponse("No wallet configuration found for given walletId")

    return OIDCSession(
      id = UUID.randomUUID().toString(),
      authRequest = authRequest,
      vpTokenClaim = vpTokenClaim,
      wallet = wallet
    ).also {
      sessionCache.put(it.id, it)
    }
  }

  fun getOIDCSession(id: String): OIDCSession? {
    return sessionCache.getIfPresent(id.replaceFirst("urn:ietf:params:oauth:request_uri:",""))
  }

  fun updateOIDCSession(session: OIDCSession) {
    sessionCache.put(session.id, session)
  }

  val oidcApiPath: String = "api/oidc"
  val oidcApiUrl: String get() = "${IDPConfig.config.externalUrl}/$oidcApiPath"

  fun getWalletRedirectionUri(session: OIDCSession): URI {
    val siopReq = VerifierManager.getService().newRequest(
      tokenClaim = session.vpTokenClaim,
      state = SIOPState(IDP_TYPE, session.id).encode()
    )
    return URI.create("${session.wallet.url}/${session.wallet.presentPath}?${siopReq.toUriQueryString()}")
  }

  fun getIdTokenFor(session: OIDCSession): String {
    return idTokenProvider.generateToken(session)
  }

  fun getAccessTokenFor(session: OIDCSession): AccessToken {
    return BearerAccessToken(
      accessTokenProvider.generateToken(session),
      EXPIRATION_TIME.seconds,
      Scope(OIDCScopeValue.OPENID)
    )
  }

  fun getTokensFor(code: String, redirect_uri: String): OIDCTokenResponse {
    val session = getOIDCSession(code) ?: throw BadRequestResponse("Invalid authorization code")
    if(!session.authRequest.redirectionURI.equals(URI.create(redirect_uri)))
      throw ForbiddenResponse("Redirection URI doesn't match OIDC session for given code")
    return OIDCTokenResponse(
      OIDCTokens(
        getIdTokenFor(session),
        getAccessTokenFor(session),
        RefreshToken()
      )
    )
  }

  val accessTokenProvider = JWTProvider(
    jwtAlgorithm,
    { session: OIDCSession, alg: Algorithm? ->
      JWT.create().withKeyId(keyId.id).withSubject(session.id).withAudience(session.authRequest.clientID.value).sign(alg)
    },
    JWT.require(jwtAlgorithm).build()
  )

  val idTokenProvider = JWTProvider(
    jwtAlgorithm,
    { session: OIDCSession, alg: Algorithm? ->
      JWT.create()
        .withKeyId(keyId.id)
        .withSubject(session.verificationResult!!.subject)
        .withIssuer("${IDPConfig.config.externalUrl}/api/oidc")
        .withIssuedAt(Date())
        .withAudience(session.authRequest.clientID.value)
        .apply {
          session.authRequest.customParameters["nonce"]?.firstOrNull()?.let { withClaim("nonce", it) }
          if(session.authRequest.responseType == ResponseType.IDTOKEN) {
            // add full user info to id_token, if implicit flow, with id_token only
            withPayload(getUserInfo(session).toJSONObject())
          } else if(session.authRequest.customParameters.containsKey("claims")) {
            session.authRequest.customParameters["claims"]?.firstOrNull()?.let {
              OIDCClaimsRequest.parse(it)
            }?.let { claims ->
              claims.idTokenClaimsRequest?.getClaimNames(false)?.let { idTokenClaims ->
                val userInfo = getUserInfo(session).toJSONObject()
                withPayload(userInfo.filterKeys { k -> idTokenClaims.contains(k) })
              }
            }
          }
        }
        .sign(alg)
    },
    JWT.require(jwtAlgorithm).build()
  )

  fun authorizeClient(clientID: String, clientSecret: String): Boolean {
    return IDPConfig.config.clients?.get(clientID)?.let { idpClient ->
      idpClient.clientId == clientID && idpClient.clientSecret == clientSecret
    } ?: false
  }

  fun verifyClientRedirectUri(clientID: String, redirectUri: String): Boolean {
    return IDPConfig.config.clients?.get(clientID)?.let { idpClient ->
      idpClient.clientId == clientID && (idpClient.redirectUris?.contains(redirectUri) ?: idpClient.allowAllRedirectUris)
    } ?: false
  }

  fun decodeAccessToken(decodedJWT: DecodedJWT): OIDCSession {
    val session = sessionCache.getIfPresent(decodedJWT.subject) ?: throw JWTDecodeException("Invalid oidc session id")
    if(!decodedJWT.audience.contains(session.authRequest.clientID.value)) throw JWTDecodeException("Invalid audience for session")
    return session
  }

  fun populateUserInfoClaims(claimBuilder: JWTClaimsSet.Builder, session: OIDCSession) {
    if(session.verificationResult?.vp_token == null) throw BadRequestResponse("No vp_token received from SIOP response")

    // populate vp_token claim, if specifically requested in auth request
    if(OIDCUtils.getVCClaims(session.authRequest).vp_token != null) {
      claimBuilder.claim("vp_token", session.verificationResult!!.vp_token!!.encode())
    }

    // populate claims based on OIDC Scope, and/or claims requested in auth request
    (session.authRequest.scope?.flatMap { s -> IDPConfig.config.claimMappings?.mappingsForScope(s) ?: listOf() } ?: listOf())
      .plus(session.authRequest.customParameters["claims"]?.flatMap { c -> IDPConfig.config.claimMappings?.mappingsForClaim(c) ?: listOf() } ?: listOf())
      .toSet().forEach { m ->
        val credential = session.verificationResult!!.vp_token!!.verifiableCredential.firstOrNull{ c -> c.type.contains(m.credentialType) } ?: throw BadRequestResponse("vp_token from SIOP resposne doesn't contain required credentials")
        val jp = JsonPath.parse(credential.json)
        val value = m.valuePath.split(" ").map { jp.read<Any>(it) }.joinToString(" ")
        claimBuilder.claim(m.claim, value)
      }
  }

  fun getUserInfo(session: OIDCSession): UserInfo {
    val verificationResult = session.verificationResult ?: throw BadRequestResponse("SIOP request not yet verified")
    val claimBuilder = JWTClaimsSet.Builder().subject(verificationResult.subject)
    populateUserInfoClaims(claimBuilder, session)
    return UserInfo(
      claimBuilder.build()
    )
  }

  private fun errorDescriptionFor(verificationResult: SIOPResponseVerificationResult): String {
    verificationResult.subject ?: return "Subject not defined"
    verificationResult.request ?: return "No SIOP request defined"
    if(!verificationResult.id_token_valid) return "Invalid id_token"
    val vpVerificationResult = verificationResult.verification_result ?: return "Verifiable presentation not verified"
    if(!vpVerificationResult.valid) return "Verifiable presentation invalid: ${vpVerificationResult}"
    return "Invalid SIOP response verification result"
  }

  private fun generateAuthSuccessResponseFor(session: OIDCSession): String {
    return session.authRequest.responseType.map { rt ->
      when(rt) {
        ResponseType.Value.CODE -> "code=${session.id}"
        OIDCResponseTypeValue.ID_TOKEN -> "id_token=${getIdTokenFor(session)}"
        ResponseType.Value.TOKEN -> "access_token=${getAccessTokenFor(session).value}"
        else -> throw BadRequestResponse("Unsupported response_type: ${rt.value}")
      }
    }.joinToString("&", postfix = "&state=${session.authRequest.state}")
  }

  private fun fragmentOrQuery(session: OIDCSession) =
    when(session.authRequest.impliedResponseMode()) {
      ResponseMode.FRAGMENT -> "#"
      else -> "?"
    }

  override fun continueIDPSessionForSIOPResponse(sessionId: String, verificationResult: SIOPResponseVerificationResult): URI {
    val session = getOIDCSession(sessionId) ?: throw BadRequestResponse("OIDC session invalid or expired")
    if(verificationResult.isValid) {
      session.verificationResult = verificationResult
      updateOIDCSession(session)
      return URI.create(
        "${session.authRequest.redirectionURI}" +
        fragmentOrQuery(session) +
        generateAuthSuccessResponseFor(session)
      )
    } else {
      return URI.create(
        "${session.authRequest.redirectionURI}" +
        fragmentOrQuery(session) +
        "error=invalid_request" +
        "&error_description=${URLEncoder.encode(errorDescriptionFor(verificationResult), StandardCharsets.UTF_8)}" +
        "&state=${session.authRequest.state}"
      )
    }
  }

  override val IDP_TYPE = IDPType.OIDC
}
