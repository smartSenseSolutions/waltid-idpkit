package id.walt.idp.siop

import id.walt.idp.IDPFactory
import id.walt.idp.context.ContextFactory
import id.walt.idp.context.ContextId
import id.walt.verifier.backend.SIOPResponseVerificationResult
import id.walt.verifier.backend.VerifierManager
import io.javalin.http.BadRequestResponse
import java.net.URI

class SIOPManager : VerifierManager() {

    override val verifierContext
        get() = ContextFactory.getContextFor(ContextId.SIOP)

    override fun getVerificationRedirectionUri(verificationResult: SIOPResponseVerificationResult, uiUrl: String?): URI {
        val siopState = SIOPState.decode(verificationResult.state) ?: throw BadRequestResponse("Invalid state")
        return IDPFactory.getIDP(siopState.idpType)
            .continueIDPSessionForSIOPResponse(siopState.idpSessionId, verificationResult)
    }
}
