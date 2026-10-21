# Berzerk Coletor (Android)

App oficial do coletor RFID do CD (Urovo DT50, Android). Fala só com o **Nexus**
(`api-nexus.cloud.berzerk.com.br`): o operador entra com a conta Google
@berzerk, confere o recebimento de lotes lendo as etiquetas UHF e manda
leituras avulsas pra Revisão RFID. Substitui o app da iTag e o APK provisório
que lia o Supabase do legado.

## O que faz (v0.1)

| Tela | O que é | Nexus |
|---|---|---|
| Login | Conta do aparelho (webadmin@berzerk.com.br): "Entrar" — Google via Cognito (pool staff), code + PKCE, callback `berzerkrfid://auth/callback`. Id token renovado em silêncio. Depois do login, cada operador se identifica pelo rosto (linha abaixo) | app client `berzerk-nexus-prod-coletor-android` (berzerk-infra #263) |
| Identificação do operador | Câmera frontal, "vire o rosto e volte" (ML Kit, só liveness simples), uma foto jpeg em memória → `POST /android/operador/identificar` (multipart `foto`). O token devolvido vai no header `x-berzerk-operador` em toda request autenticada e o Nexus trata o operador como autor. 15 min sem toque/tecla/gatilho encerram a sessão no app; 12 h no servidor; 403 `operador_sessao_invalida` limpa só a sessão de operador e volta pra tela facial (a conta do aparelho segue). "Trocar operador" = `DELETE /android/operador/sessao` | `android/operador/*` |
| Recebimento | Lotes no galpão / a caminho → abrir o lote → gatilho lê → leituras sobem a cada 4 s → grade lido × esperado por tamanho → Confirmar. Divergência pede aceite, causa + motivo e, acima da tolerância, PIN de supervisor | `revisao-rfid/conferencia-lote/*` (permissão `revisao-rfid:conferir`, papel `coletor-recebimento`) |
| Leitura livre | Conta peças ao vivo por produto × tamanho usando a base EPC → produto baixada no aparelho; "Enviar pro Nexus" manda o acumulado | `GET /revisao-rfid/itag/epcs` + `/itag/imagens` (base), `POST /rfid/upload` (token do coletor) |
| Configurações | Token do coletor (gerado em Revisão RFID → Coletores), potência e bipe do leitor, download/atualização da base | — |
| Atualização | Versão nova = diálogo; abaixo da mínima (426 `app_desatualizado`) = tela de bloqueio que baixa o APK e abre o instalador | `GET /android/release`, `android_versao_minima` (admin → Configurações) |

Gatilho físico: keycodes 523 (gatilho) e 515 (PTT), mais 520–522 (teclas laterais).
O botão ▶ da tela faz o mesmo.

## Compliance

- **Nenhum segredo no app nem no repo.** O código só carrega identificadores
  públicos (client id, domínio do Cognito, URL da API — `gradle.properties`).
  Sessão e token do coletor ficam em `EncryptedSharedPreferences` (chave no
  Keystore do Android) e nunca saem do aparelho nem aparecem em log.
- **Identidade = Nexus.** O aparelho loga com UMA conta compartilhada
  (webadmin) e o operador se identifica pelo rosto; o Nexus casa o rosto, trata
  o operador como autor (trilha por pessoa, permissão pelo papel) e revoga a
  sessão. A foto só existe em memória: nunca vai pra disco nem pra log.
- **Keystore de release** vive nos secrets do repo (`ANDROID_KEYSTORE_*`) com
  cópia em `berzerk-android/release-keystore` no Secrets Manager da conta
  Berzerk. Perder a chave = ninguém atualiza o app instalado.
- **SDK da Urovo** (`app/libs/URFIDLibrary-v2.6.0609.aar`) é proprietário,
  redistribuído pela própria Urovo em
  [UrovoSdkCenter/RFID_Android](https://github.com/UrovoSdkCenter/RFID_Android).

## Desenvolvimento

Requisitos: JDK 17 e Android SDK (platform 34, build-tools 34). Sem Android
Studio dá pra compilar com `./gradlew` (crie `local.properties` com `sdk.dir=`).

```sh
./gradlew assembleDebug            # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
adb install -r app/build/outputs/apk/debug/berzerk-coletor-*.apk
adb logcat -s Rfid Auth Nexus Update ItagSync Facial
```

Apontar pra outro Nexus/Cognito: sobrescreva `NEXUS_API_BASE_URL`,
`COGNITO_*` em `~/.gradle/gradle.properties` ou com `-P`.

## Release

1. Bump `VERSION_NAME` em `gradle.properties` (semver; o `versionCode` é
   derivado: `1.2.3` → 10203).
2. Commit, `git tag vX.Y.Z -m "notas"`, `git push --follow-tags`.
3. O workflow `release` assina o APK, gera `latest.json` e publica o GitHub
   Release. O Nexus passa a oferecer a versão em `GET /android/release`.
4. Pra **forçar** a atualização: no Nexus, admin → Configurações → "Berzerk
   Coletor (Android)" → versão mínima = X.Y.Z. Todo coletor abaixo disso toma
   426 nas rotas da Revisão RFID e só volta a operar depois de atualizar.
