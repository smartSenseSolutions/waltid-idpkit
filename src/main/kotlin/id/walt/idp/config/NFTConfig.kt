package id.walt.idp.config

import com.beust.klaxon.Klaxon
import id.walt.idp.nfts.NFTClaim
import id.walt.verifier.backend.WalletConfiguration
import id.walt.webwallet.backend.config.ExternalHostnameUrl
import id.walt.webwallet.backend.config.externalHostnameUrlValueConverter
import java.io.File

data class NFTConfig (
  val nftWallet: WalletConfiguration = WalletConfiguration(
    "idpkit-connect-wallet",
    "/connect-wallet",
    "", "",
    "IDP Kit connect wallet"
  )
) {
  companion object {
    val CONFIG_FILE = "${id.walt.WALTID_DATA_ROOT}/config/nft-config.json"
    lateinit var config: NFTConfig
    init {
      val cf = File(CONFIG_FILE)
      if(cf.exists()) {
        config = Klaxon().fieldConverter(ExternalHostnameUrl::class, externalHostnameUrlValueConverter).parse(cf) ?: NFTConfig()
      } else {
        config = NFTConfig()
      }
    }
  }
}
