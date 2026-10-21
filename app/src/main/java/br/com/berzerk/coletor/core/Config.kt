package br.com.berzerk.coletor.core

import br.com.berzerk.coletor.BuildConfig

/** Identificadores PÚBLICOS do Nexus/Cognito (vêm de `gradle.properties`). Nenhum segredo mora aqui. */
object Config {
    val apiBaseUrl: String = BuildConfig.NEXUS_API_BASE_URL
    val cognitoDomain: String = BuildConfig.COGNITO_HOSTED_UI_DOMAIN
    val cognitoIssuer: String = BuildConfig.COGNITO_ISSUER
    val cognitoClientId: String = BuildConfig.COGNITO_CLIENT_ID
    val redirectUri: String = BuildConfig.COGNITO_REDIRECT_URI
    val logoutUri: String = BuildConfig.COGNITO_LOGOUT_URI

    /** Semver puro (sem `-debug`): é o que vai no header `x-berzerk-android-version`. */
    val versao: String = BuildConfig.VERSION_NAME.substringBefore('-')
    const val VERSAO_HEADER = "x-berzerk-android-version"

    /** Permissão que libera a conferência de lote e a base iTag no Nexus. */
    const val PERMISSAO_CONFERIR = "revisao-rfid:conferir"
    const val PERMISSAO_OPERATE = "revisao-rfid:operate"
}
