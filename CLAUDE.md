# CLAUDE.md

App Android (Kotlin + Jetpack Compose, minSdk 26) do coletor RFID do CD — Urovo
DT50 — que fala SÓ com o Nexus. Textos de UI, comentários e commits em pt-BR;
commits `tipo(escopo): descrição`, branches `feat|fix/<slug>`, squash merge
(skill `pr` do plugin `berzerk-core`). Leia o README antes de mexer.

## Mapa

- `app/src/main/java/br/com/berzerk/coletor/`
  - `core/` — `Config` (ids públicos de `gradle.properties`), `Prefs` (cifrado), `Graph` (singletons, sem DI).
  - `auth/AuthManager` — Cognito code+PKCE via AppAuth; Bearer = **id token**; refresh 5 min antes do `exp`; recusa = sessão fora.
  - `auth/OperadorSessao` — sessão do operador (reconhecimento facial): token em `Prefs`, header `x-berzerk-operador`, inatividade de 15 min.
  - `api/` — `NexusApi` (Retrofit), `Dtos` (espelho dos zod de `@berzerk/contracts`, todo campo opcional com default), `NexusClient` (header `x-berzerk-android-version`, Bearer, 401 renova uma vez, 426 → `UpdateManager`), `ApiException`.
  - `rfid/RfidReader` — SDK oficial da Urovo (`com.ubx.usdk`): `RFIDSDKManager.init` → `registerCallback` → `startInventory/stopInventory`. Gatilho = KeyEvent 523/515 na `MainActivity`.
  - `data/` — Room v5 (`epcs`, `imagens`, `sync_estado`, `modelos`, `leituras` PK origem+referencia+epc + `pastaId` nula até o SALVAR, `inventario_cache`; `Modelos` = parse do `nome` da iTag + resumos por modelo, `modelos` reindexada após cada sync) + `ItagSync` (páginas de 20k, keyset + `desde`, retoma de onde parou).
  - `update/UpdateManager` — release do Nexus, download com sha256, instalador do Android.
  - `upload/` — `UploadService` + `FilaEnvio`: leituras do Room (`leituras`, `enviadoEm = null`) sobem em blocos de 5.000 (texto puro, `x-coletor-dispositivo`, sem token) SEMPRE pra `POST /revisao-rfid/sessoes/{pastaId}/leituras` (inventário, revisão e livre; o `/leituras` genérico só serve a Conferência). Pasta = sessão de contagem, escolhida no SALVAR: `enviarComRetry(origem, referencia, pasta)` grava o `pastaId` nos pendentes e a fila agrupa por `(origem, referencia, pastaId)`; linha sem `pastaId` (legado) não sobe sozinha e entra no próximo SALVAR. `enviadoEm` só com 2xx; rede/5xx retenta (5/15/60 s), 4xx para; 409 `sessao_nao_aceita_leituras` = pasta fechada (`ResultadoEnvio.PastaFechada`, linhas seguem pendentes). `EnvioPendentesWorker` (WorkManager, rede, `KEEP` `upload-pendentes`) reenvia o que já tem pasta.
  - `ui/` — Compose: `App` (rotas), `telas/*` (Login, Recebimento (card estilo Nexus com `FotoProduto`), Facial + FacialViewModel (câmera frontal, ML Kit, liveness), Home, Recebimento, Conferência + ViewModel (mesmo layout da Revisão por modelo: grade por tamanho com EAN, contadores, console, rodapé LER/Zerar + Confirmar), Revisão (lista de modelos), `LeituraColetor` (tela ÚNICA de leitura persistida: inventário, modelo e "Ler tudo"; `RevisaoModelo`/`Leitura` são só atalhos), Pastas (lista `inventario`: abertas/fechadas + Nova pasta; pasta `inventario/{id}` com KPI/chips/cards; leitura em `inventario/{id}/ler`, onde a pasta da rota só vem pré-selecionada no SALVAR), Config, AtualizacaoOverlay), `comum/` (inclui `FotoProduto`: recorte com zoom + tela cheia; `TileTamanho`, `Contador`, `ConsoleLeituras`, `RodapeLeitura`, `IndicadorEnvio`, `SalvarEmPasta` (seletor "Salvar em…" + diálogo Nova pasta; lógica em `data/Pastas`) compartilhados entre Revisão, Inventário e Conferência; a Conferência ainda mantém seus EPCs em memória), `theme/`.
- `app/libs/URFIDLibrary-v2.6.0609.aar` — SDK proprietário da Urovo (doc em UrovoSdkCenter/RFID_Android).

## Regras

- Sessão de operador (`x-berzerk-operador`) só em `Prefs`; 403 `operador_sessao_invalida` limpa só a sessão de operador, nunca a do Cognito; foto nunca vai pra disco nem log.
- Status/decisão de negócio é do Nexus: o app NUNCA decide divergência, tolerância ou PIN sozinho — só repete o que o 422 mandou (`conferencia_diverge` → `exige_justificativa` → `exige_pin`).
- Nada de segredo no código/repo. Sessão só em `Prefs` (o aparelho se identifica pelo `ANDROID_ID`; não existe token de coletor). Não logar token, PIN nem Bearer (`redactHeader` no OkHttp).
- Contrato novo no Nexus → espelhar em `api/Dtos.kt` com defaults (app velho × API nova não pode quebrar o parse).
- Versão: `VERSION_NAME` em `gradle.properties`; tag `vX.Y.Z` publica (ver README). O header de versão é o semver sem sufixo.
- Sem Android Studio aqui: `./gradlew assembleDebug` com JDK 17 (`~/.local/opt/jdk17`) e SDK em `~/Android/Sdk`.
