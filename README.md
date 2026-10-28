# Berzerk Coletor (Android)

App oficial do coletor RFID do CD (Urovo DT50, Android). Fala só com o **Nexus**
(`api-nexus.cloud.berzerk.com.br`): o operador entra com a conta Google
@berzerk, confere o recebimento de lotes lendo as etiquetas UHF e manda
leituras avulsas pra Revisão RFID. Substitui o app da iTag e o APK provisório
que lia o Supabase do legado.

## O que faz (v0.3.x)

| Tela | O que é | Nexus |
|---|---|---|
| Login | Conta do aparelho (webadmin@berzerk.com.br): "Entrar" — Google via Cognito (pool staff), code + PKCE, callback `berzerkrfid://auth/callback`. Id token renovado em silêncio. Depois do login, cada operador se identifica pelo rosto (linha abaixo) | app client `berzerk-nexus-prod-coletor-android` (berzerk-infra #263) |
| Identificação do operador | Câmera frontal, "pisque os olhos" (ML Kit, só liveness simples: uma piscada com os dois olhos, de frente), a foto é o próprio frame da análise (720×960) no instante da piscada, sem `takePicture`, só em memória → `POST /android/operador/identificar` (multipart `foto`). O token devolvido vai no header `x-berzerk-operador` em toda request autenticada e o Nexus trata o operador como autor. 15 min sem toque/tecla/gatilho encerram a sessão no app; 12 h no servidor; 403 `operador_sessao_invalida` limpa só a sessão de operador e volta pra tela facial (a conta do aparelho segue). "Trocar operador" = `DELETE /android/operador/sessao` | `android/operador/*` |
| Recebimento | Lotes no galpão / a caminho → abrir o lote → gatilho lê → leituras sobem a cada 4 s → grade lido × esperado por tamanho (mesmo layout da Revisão por modelo: tiles com EAN, contadores, console das leituras, `Zerar`/`LER`; o Zerar sobe o que falta e limpa só a vista do aparelho, a grade do Nexus não zera; Confirmar para a leitura, sobe o pendente e só então confirma) → Confirmar. A lista é um card no estilo do Recebimento do Nexus web (foto, lote, estampa, grade por tamanho, oficina, check-in no galpão; campos novos opcionais). Divergência pede aceite, causa + motivo e, acima da tolerância, PIN de supervisor | `revisao-rfid/conferencia-lote/*` (permissão `revisao-rfid:conferir`, papel `coletor-recebimento`) |
| Revisão RFID | Lista de modelos da base offline (categoria/modelo vêm do `nome` da iTag, `"Oversized - Alfa - G - Branca"`; o tamanho pode estar no meio e sai do modelo) com busca e chips de categoria. Abrir um modelo = leitura com grade por tamanho + EAN, contadores (do modelo / sem cadastro / outros / total), console de leituras e **SALVAR**, que abre "Salvar em…" e manda só as peças do modelo pra pasta escolhida. Toque longo na lista seleciona vários modelos: a leitura mostra contadores gerais + uma seção por modelo com grade por tamanho, e o SALVAR manda só as peças dos modelos selecionados. "Ler tudo" = leitura livre, sem filtro de modelo. A tabela local `modelos` é reindexada após cada sync (e quando a regra do parse muda, `VERSAO_INDICE`). A foto é recortada na estampa e abre em tela cheia ao tocar; casa por EAN e, na falta, pelo prefixo de 12 dígitos (o 13º só separa o tamanho) | `GET /revisao-rfid/itag/epcs` + `/itag/imagens` (base), `POST /revisao-rfid/sessoes/{pasta}/leituras` (header `x-coletor-dispositivo`) |
| Pastas | Pasta = contagem (sessão `contagem` do Nexus). A lista mostra as abertas (várias) e as fechadas; "Nova pasta" pede só o nome (`coletorIds` = id deste aparelho, que o Nexus devolve em toda leitura; 409 `pasta_nome_em_uso` e, no Nexus velho, `sessao_aberta_existente`). A pasta mostra KPI de peças, busca, chips de categoria e um card por produto com contagem por tamanho; o cálculo demora 15–25 s, então a última resposta fica em cache no Room e só recarrega no pull-to-refresh ou depois de um envio. **LER** abre a tela de leitura (a mesma da Revisão), que grava TODO EPC lido (inclusive sem cadastro); a pasta da rota só vem pré-selecionada no SALVAR | `GET/POST /revisao-rfid/sessoes`, `GET /revisao-rfid/sessoes/{id}/inventario`, `POST /revisao-rfid/sessoes/{id}/leituras` |
| Leituras e envio | Toda leitura (pasta, revisão, livre) é gravada no Room (`leituras`, uma linha por EPC por origem/referência) e sobrevive a sair da tela/fechar o app. **SALVAR abre "Salvar em…"**: pastas abertas (`abertaEm desc`, a da rota pré-selecionada no topo, com nome, "aberta há X" e peças), "Nova pasta…" e "Salvar nesta pasta"; sem rede mostra as pastas do último cache e, sem cache, recusa ("Sem rede pra listar as pastas"). O envio vai sempre pra `POST /revisao-rfid/sessoes/{id}/leituras`, em blocos de 5.000; a pasta fica gravada nas linhas pendentes (`pastaId`) e o reenvio (retry e WorkManager) vai pra ela. Linha sem pasta (versão anterior) espera o próximo SALVAR. `enviadoEm` só com 2xx; rede/5xx retenta (5/15/60 s); 409 `sessao_nao_aceita_leituras` = pasta fechou: aviso, seletor reaberto e as leituras seguem pendentes; depois de subir, "Salvo em {pasta}". Sair com leitura não enviada pergunta antes | `POST /revisao-rfid/sessoes/{id}/leituras` |
| Configurações | Potência e bipe do leitor, base de etiquetas (atualizar/pausar/apagar) e imagens do catálogo — dois downloads independentes, cada um com seu erro e sua data. "Sair da conta do aparelho" abre o `/logout` do Cognito num Custom Tab (senão o cookie de 1 h do navegador reloga a mesma conta) e só limpa a sessão local quando o callback `berzerkrfid://auth/logout` volta (ou após 10 s) | — |
| Impressão | Etiquetas na **Niimbot B31** por Bluetooth Low Energy (protocolo = port do niimbluelib, sem Nexus nem chave de API). Lista de produtos da base local (mesma busca/chips da Revisão) → tocar nos tamanhos **seleciona** (não imprime); o rodapé "IMPRIMIR (n etiquetas)" enfileira um trabalho por tamanho × "cópias por tamanho" e limpa a seleção ("Todos"/"Nenhum" no rodapé). A **fila fica visível** (imprimindo em verde, esperando em cinza, "Limpar fila"). Etiqueta: tamanho gigante + EAN + nome, 1 bit, 600 px de largura; nome/EAN **embaixo ou em cima** (Ajustes). Ajustes: buscar/parear a impressora, tamanho do rolo (padrão 80×100 mm), tipo de papel, densidade e **modo de impressão** (Automático = D110M V4 na B31; B1 e D110 de reserva se sair em branco) e **envio rápido** (várias linhas por escrita BLE; desligue se faltarem linhas). Permissões: Android ≥ 12 `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`; Android 11 (DT50) `BLUETOOTH*` + localização (a varredura BLE exige a Localização do aparelho ligada). O app não liga o Bluetooth sozinho | — |
| Atualização | Versão nova = diálogo; abaixo da mínima (426 `app_desatualizado`) = tela de bloqueio que baixa o APK e abre o instalador | `GET /android/release`, `android_versao_minima` (admin → Configurações) |

Gatilho físico: keycodes 523 (gatilho) e 515 (PTT), mais 520–522 (teclas laterais).
O botão ▶ da tela faz o mesmo.

## Compliance

- **Nenhum segredo no app nem no repo.** O código só carrega identificadores
  públicos (client id, domínio do Cognito, URL da API — `gradle.properties`).
  Sessão e id do coletor ficam em `EncryptedSharedPreferences` (chave no
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
