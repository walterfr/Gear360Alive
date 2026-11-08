# Gear360 Alive: planejamento

Atualizado em 2026-11-06. Este documento substitui as decisões de conexão e firmware do `AGENTS.md`.

## 1. Objetivo e restrições

App Android simples, no estilo do app Insta360, para a Samsung Gear 360 2017 (SM-R210).

- **Câmera:** somente SM-R210. O SM-C200 fica sem garantia de teste.
- **Firmware:** original. Não instalar firmware nem add-on na câmera.
- **Risco:** a câmera é única e não pode ser perdida.
- **Plataforma:** somente Android.

## 2. Regras de segurança da câmera

Valem para todo o projeto e têm prioridade sobre qualquer funcionalidade.

1. **Nada de firmware.** O app não baixa, grava nem envia `.bin`, `fwupgrade.txt`, `info.tg` ou `cmd.tg`.
2. **Comandos por lista permitida.** O cliente da câmera só envia ações listadas no código. Formatação de cartão, atualização de firmware e comandos não documentados ficam fora. Um teste unitário garante a lista.
3. **Primeiro só leitura.** Toda sonda nova começa com GET e sem alterar estado. Comandos de escrita entram um a um, com o resultado registrado.
4. **Originais intocáveis.** Stitching e edição sempre geram arquivo novo. Apagar da câmera exige confirmação.
5. **Cartão protegido.** Copiar o cartão antes de cada sessão de teste com escrita.
6. **Sem update de firmware Samsung.** Registrar a versão atual e não atualizar, mesmo para a ARB2.

Script em cartão (`info.tg`) não altera o firmware, mas roda como root. Fica fora do plano, salvo decisão explícita (seção 8).

## 3. Decisão sobre o repositório: manter

Não apagar. Nada no estado atual justifica recomeçar.

**Evidências:**

- `main` é o histórico do Revive360 (`e461985`, igual ao HEAD atual do upstream) mais um commit próprio (`6f27c5c`, cerca de 5,6 mil linhas). Isso permite receber atualizações do upstream por merge.
- `./gradlew testDebugUnitTest assembleDebug` passou em cópia isolada: 54 testes, 0 falhas, 1 ignorado.
- Stitcher de vídeo, Reframe, parsers OPAX/`vrot` e leitura de TTTS são úteis com firmware original.
- A licença GPL-3.0 exige manter a atribuição, e o histórico já faz isso.

**Problemas encontrados:**

| Problema | Evidência | Ação |
|---|---|---|
| App principal não conversa com câmera original | Pareamento só espera `G360R-` e porta 8360 | M3 |
| Sem cliente UPnP/SOAP | Nenhuma referência a 7676, 7679 ou 9001 no app | M3 |
| `OscClient` só é usado na tela de firmware | Busca por referências em `app/src/main` | M3 |
| Instalador de firmware acessível em Ajustes | `SettingsScreen.kt:170`, `FirmwareManager.kt` | **M0: remover** |
| URLs de firmware no seu repo, sem release publicada | `FirmwareManager.kt:41,49` | **M0: remover** |
| Identidade igual à do upstream | `applicationId` igual; README aponta Play e releases dele | M0 |
| `AGENTS.md` define add-on como modo principal | Seção 1 | M0 |
| `AGENTS.md` trata Gear360App como GPL-3.0 | Repo sem arquivo de licença (API retorna 404) | M0: corrigir e revisar a origem do parser `00VR` |
| Servidor OBS na porta 8554 sem bind restrito nem autenticação | `LiveStreamer.kt:77` | M0: ocultar |
| Galeria do telefone só lê a pasta do próprio app | `PhoneMedia.kt:53` | M2 |
| Stitch R210 usa aproximação equidistante, sem o polinômio OCamCalib | `CameraMetadata.kt:37` | M2: medir |
| Funcionalidades do commit próprio sem validação registrada em câmera real | Só testes unitários no repo | M2 e M6 |

## 4. Caminhos sem mod no R210

Esta é a maior incerteza do projeto. Nenhum dos caminhos 2, 3 ou 4 está comprovado por nós.

| # | Caminho | Oferece | Evidência | Status |
|---|---|---|---|---|
| 1 | Cartão SD por leitor/OTG | Importar, stitching, edição | Arquivos trazem calibração e `vrot` (docs do Revive360, gear360-raw) | Seguro; é a base |
| 2 | "Connect to Street View" (OSC, porta 80) | Foto remota, ISO, obturador, WB, listar, baixar, apagar, bateria, espaço. Sem vídeo e sem live view | Revive360 `15_r210_port.md` §7 (análise do firmware, marcada como não testada); vitorio confirma o modo e stitch na câmera em cerca de 13 s | A testar |
| 3 | "Connect to iOS/Android" e UPnP/SOAP (7676, 7679, 9001) | Controle completo: foto, gravação, ajustes, arquivos, live view 2560x1280 | **Comprovado na sua câmera em 2026-11-06:** servidor UPnP ativo pelo menu, sem Bluetooth e sem mod (seção 13). Comandos ainda não testados | **Caminho principal** |
| 4 | Bluetooth via Samsung Accessory Service | Liga o Wi-Fi sem usar o menu da câmera e entrega nome e senha da rede; depois o controle segue pelo caminho 3 | Gear360App usa esse caminho; relato de falha no Android 14 em Samsung ([Gear360_OSS#5](https://github.com/KieronQuinn/Gear360_OSS/issues/5)); SDK proprietário | Opcional: só para ligar o Wi-Fi sem usar o menu |

BLE para acordar a câmera (como no Revive360) depende do add-on. Em firmware original, o Wi-Fi sobe pelo menu da câmera ou, se o caminho 4 funcionar, pelo Samsung Accessory Service.

**Licença do caminho 4:** o Gear360App depende do SDK proprietário da Samsung, e o Gear360 Alive é GPL-3.0. Se o teste funcionar, é preciso decidir como integrar antes de escrever código (seção 8).

## 5. Escopo do produto

**Telas:** Álbum, botão central Câmera, Ajustes. A edição abre a partir do Álbum.

| Funcionalidade | Situação no repo | Decisão |
|---|---|---|
| Importar do cartão | Falta | Lançamento 0.1 |
| Stitching de foto e vídeo | Existe; validar | 0.1 |
| Viewer e player 360 | Existe; validar | 0.1 |
| Exportar para Google Photos e YouTube | Existe; validar | 0.1 |
| Conexão guiada e arquivos pela câmera | Falta | 0.2 |
| Foto remota | Falta para stock | 0.2 |
| Gravação remota, ajustes, live view | Falta para stock | 0.3, se o caminho 3 existir |
| Reframe com keyframes, horizonte, little planet | Existe; validar | 0.4 |
| Deep Track, TimeShift, Nadir, GPX HUD | Existe; não validado | Oculto até validar |
| PureShot, Starlapse | Dependem de captura controlada | Oculto até validar |
| OBS/webcam | Exige segurança e caminho 3 | Oculto |
| RAW, BLE, LEDs, firmware OTA/OTG | Exigem add-on | Fora |
| Nuvem, conta, comunidade, IA generativa | Não existe | Fora |

## 6. Marcos

Tamanhos são relativos (P, M, G), não prazos.

### M0: arrumar o repositório (P)

**Status (2026-11-06):** feito na branch local `m0-stock-first`, sem commit. Pendente: `NOTICE.md` registra como questão aberta a origem de `OscClient.kt` e do parser `00VR`.

- Criar branch de trabalho e remote `upstream`.
- Remover a tela e o gerenciador de firmware do build, com um teste que garanta a ausência da rota.
- Ocultar funcionalidades não validadas e o servidor OBS.
- Trocar `applicationId` e nome visível. Manter o pacote Kotlin, para facilitar merges com o upstream.
- Reescrever o README com atribuição ao Revive360 e aviso de projeto independente.
- Corrigir o `AGENTS.md`: remeter a este plano e incluir as regras da seção 2.
- Remover `site/`, `fastlane/` e `.github/workflows/pages.yml` (materiais do upstream).
- Manter `camera/` sem uso, para uma eventual decisão futura.
- Corrigir notas de licença: MIT do gear360-revive e origem do parser `00VR`.

**Pronto quando:** build e testes passam, e o APK não contém caminho para firmware.

### M1: sonda sem risco (P)

**Rodada A: Wi-Fi, pelo PC.** O PC fica online pelo cabo e entra na rede da câmera pelo Wi-Fi.

- Script Python só com biblioteca padrão, preso ao adaptador Wi-Fi, com portas e URLs fixas: testar TCP em 80, 7676, 7679 e 9001, e fazer GET em `/osc/info`, `/osc/state` e `:7676/smp_2_`. Sem SOAP, sem POST e sem abrir stream.
- Rodar nos modos "Connect to iOS", "Connect to Android" e "Connect to Street View". Guardar respostas como fixtures.
- Identificar a versão do firmware e o endereço IP de cada modo.
- Copiar do cartão: 1 foto em cada tamanho e 1 vídeo curto em cada modo da seção 12. Estes serão as amostras de teste.

**Rodada B: Bluetooth, pelo tablet.** Só depois da rodada A, e com aprovação para instalar o app de teste.

- Compilar o Gear360App a partir do código-fonte, sem alterá-lo, e revisar antes as mensagens que ele envia. Não usar a tela de teste nem funções fora de conectar e ler informações.
- Parear a câmera e conceder a permissão de Bluetooth ao Samsung Accessory Service.
- Verificar: o serviço conecta sem fechar? A câmera informa rede e senha? O Wi-Fi sobe? A porta 7676 responde nesse estado?
- Registrar logs. Se o serviço falhar, parar: não há tentativa com outras versões nem com o Gear 360 Manager, que tem fluxo de firmware.

**Resultado define o escopo:**

| Sonda | Produto |
|---|---|
| 7676 responde após menu | Controle completo sem mod; conexão pelo menu |
| 7676 responde só após Bluetooth | Controle completo, dependendo do Samsung Accessory Service e da decisão de licença |
| Só OSC responde | Foto remota; vídeo pelo botão da câmera e importação por cartão; sem live view |
| Nada responde | App offline; decidir na seção 8 |

### M2: núcleo offline, lançamento 0.1 (M)

Entrega valor sem nenhum comando à câmera.

**Status (2026-11-07):** parte sem amostras feita, sem commit. Testado no tablet com vídeos sintéticos, já apagados:

- **Importação:** por seletor de arquivos, cópia idêntica por MD5, galeria atualizada. O teste revelou que o provedor pode informar tamanho errado (`0 B`); o tamanho agora vem do arquivo aberto. Ainda falta: seleção de pasta, repetir com leitor de cartão real, e rodar em serviço (sair da tela interrompe entre arquivos).
- **Política de processamento:** `ProcessingPolicy`, com todos os modos da seção 12 e limites simulados. Avisos conferidos na tela para 1080p, 4096x2048 e 2560x1280.
- **Exportação 360 de 2560x1280 a 30 fps (3 s):** saída H.264 em 2560x1280, 30 fps, áudio e metadados esféricos. Antes tentaria 3840x1920, acima do limite do tablet. A costura não foi avaliada, porque a imagem era sintética.
- **4096x2048 H.264 a 24 fps:** o tablet recusou o decodificador, tanto no preview quanto na exportação. Sem crash, sem arquivo parcial, original intacto. Falta repetir com arquivo real da câmera, cujo codec não foi confirmado.

Fica para as amostras: OPAX, `vrot`, qualidade de costura, vídeo de 10 min, Google Photos e YouTube.

- Importação por seletor de arquivos e pasta (`OpenDocumentTree`), incluindo leitor OTG.
- Álbum unificado com originais preservados.
- Validar com amostras reais do R210: OPAX, `vrot`, stitch de foto e vídeo.
- Comparar a costura da aproximação atual com o polinômio OCamCalib e manter a mais simples que atinja qualidade aceitável.
- Stitching em serviço de primeiro plano, com progresso e cancelamento.

**Pronto quando:**

- Foto é reconhecida como 360 pelo Google Photos.
- Vídeo de 10 min em 2560x1280 a 30 fps termina no tablet de teste sem travar e com áudio sincronizado.
- Arquivo 4096x2048 real testado no tablet. Se o decodificador recusar, o app mantém o original e avisa antes de começar, em vez de falhar no meio (seção 12).
- Testes da política de processamento cobrem todos os modos da seção 12, com limites de aparelho simulados.
- YouTube reconhece o vídeo como 360.
- Os arquivos originais permanecem idênticos, por hash.

### M3: conexão com firmware original, lançamento 0.2 (M)

**Teste real (2026-11-07):** o dono relatou que os dados da câmera apareceram na tela do tablet, e o log do app confirma a conexão: rede ativa às 00:18:14, câmera em `192.168.43.1`, sem nenhuma falha de resposta e sem crash. A primeira tentativa, às 00:17:29, terminou em rede não encontrada ou recusada. O dono confirmou que os dados batiam com a câmera, que a tela dela não mudou e que ela continuou ligada. A rede caiu às 00:21:08, cerca de 3 min depois, por causa ainda não confirmada; como a câmera seguiu ligada, o desligamento automático está descartado nessa queda. O `adb` por Wi-Fi caiu durante a conexão, então o tablet saiu da rede de casa ao menos temporariamente; o efeito na internet do tablet segue sem medida.

**Um cliente por vez (hipótese):** o PC saiu da rede da câmera quando o tablet conectou, segundo o dono. Pode ser limite da câmera, mas falta testar, porque também houve orientação para tirar o PC dessa rede. Se confirmado, o app precisa avisar que outro aparelho pode perder a conexão.

**Status (2026-11-07):** primeira parte feita, sem commit: tela só de leitura (`StockCameraScreen`) com `StockClient`, que por tipo só envia `X_GetSettingInformation` e `X_GetStorage`. Testes usam respostas reais da câmera, em `app/src/test/resources/stock/`. O sistema pede confirmação de rede a cada conexão; associação por `CompanionDeviceManager` fica para depois. A interface de `CameraClient` ainda não foi extraída.

- Assistente: instrução do menu da câmera, código de 8 dígitos digitado uma vez e reconexão por `WifiNetworkSpecifier` e `CompanionDeviceManager`.
- Cliente stock só com ações da lista permitida, testado contra as fixtures do M1.
- Extrair interface de `CameraClient` apenas agora, porque passam a existir duas implementações.
- A UI usa o `Capabilities` existente para mostrar só o que o modo oferece.
- Bateria, espaço, lista, miniaturas, download com retomada e exclusão com confirmação.
- Foto remota (OSC ou SOAP `Shot`), como primeiro comando de escrita, sob a regra 3 da seção 2.

**Pronto quando:** do toque no ícone até listar arquivos em menos de 15 s, depois do primeiro uso, sem digitar nada; 20 fotos remotas seguidas sem travar.

### M4: captura completa, lançamento 0.3 (M)

Só se o caminho 3 existir.

- `StartRecord`, `StopRecord` e ajustes, um por vez.
- Live view com o decodificador atual. A documentação do R210 alerta: entre clientes, a sessão do stream precisa ser encerrada, ou o app da câmera pode ficar parado de 10 a 60 s.
- Tratar live view e captura como exclusivos até medir o comportamento real.

**Pronto quando:** cinco ciclos seguidos de live view, gravação e parada, com a câmera respondendo.

### M5: acabamento de UX (M)

- Navegação definitiva, primeiro uso guiado e mensagens de erro acionáveis.
- Português do Brasil (`values-pt-rBR`) e inglês; manter o italiano herdado.
- Acessibilidade básica: rótulos, contraste, alvos de toque.

**Pronto quando:** alguém sem instruções importa, gera e compartilha uma foto 360.

### M6: Studio, lançamento 0.4 (G)

- Validar e liberar: Reframe com keyframes, exportação 16:9, 9:16 e 1:1, horizonte por `vrot` e little planet.
- Confirmar que o R210 grava `vrot` e medir a qualidade da estabilização.
- Avaliar cada função oculta em vídeo real: liberar ou apagar. Deep Track, por exemplo, usa correlação de imagem (NCC), não IA.

**Pronto quando:** clipe de 1 min é reenquadrado e exportado em poucos toques, com áudio sincronizado.

### M7: distribuição (P)

- Assinatura de release fora do repo, APK no GitHub Releases e checksums.
- Play Store só depois: rever uso da marca "Gear 360", política de privacidade própria e requisitos da loja.
- Nenhum binário de firmware Samsung nas releases.

## 7. Testes

- **Unitários:** parsers, lista permitida, SOAP e OSC contra fixtures reais do M1.
- **Amostras:** arquivos do seu R210 fora do git, em pasta local, por tamanho e privacidade.
- **Câmera real:** checklist por lançamento, com cartão copiado antes de qualquer escrita.
- **CI:** build e testes a cada push, quando o M0 terminar.

## 8. Decisões futuras

| Decisão | Quando | Recomendação |
|---|---|---|
| Integrar Samsung Accessory Service no app | Se a rodada B do M1 funcionar | Avaliar primeiro se o caminho 3 pelo menu basta; o SDK proprietário complica a GPL-3.0 |
| Script em cartão (`info.tg`) | Se o caminho 3 falhar e o controle remoto for indispensável | Só com sua aprovação explícita; risco baixo, não zero |
| Firmware modificado | Só com segunda câmera dedicada a testes | Não usar na câmera atual |
| Contribuir com o upstream | Depois do M2 | Oferecer o stitcher de vídeo; evita manter dois caminhos |

## 9. Riscos

| Risco | Efeito | Mitigação |
|---|---|---|
| Sem controle completo no stock | Sem live view nem gravação remota | M2 entrega valor mesmo assim; matriz do M1 |
| Comando trava a câmera | Reinício necessário | Lista permitida; um comando por vez; seção 2 |
| Tablet de teste declara limite de 2560x1440 | 4K pode não processar no aparelho | Testar arquivo real; detectar capacidades; avisar antes; 2560x1280 como alvo no tablet (seção 11) |
| Stitch com costura ruim | Qualidade abaixo do Insta360 | Calibração real e comparação com amostras |
| Upstream muda rápido | Conflitos de merge | Pacote mantido; merges frequentes; `protocol/` e `camera/` pouco alterados |
| Código de origem sem licença clara | Risco de licença | Reescrever a partir do formato, se a origem não for confirmada |

## 10. Dados pendentes

Nenhum. Firmware da câmera: `R210GLU0ARB2_0.81` (seção 13).

## 11. Dispositivo de teste

Lenovo TB361FU, Android 16 (SDK 36), MediaTek MT8755, 8 GB de RAM, OpenGL ES 3.2, tela 1600x2560. Lido por `adb` em 2026-11-06. O PC de teste tem Wi-Fi.

**Codecs:** decodificadores e codificadores de hardware H.264 e HEVC declaram no máximo 2560x1440 e 489.600 blocos/s. Os de software param em 1920x1088 ou menos.

| Vídeo do R210 | Pelos limites declarados |
|---|---|
| 4096x2048 a 24 fps | Fora: largura acima de 2560 |
| 2880x1440 a 30 fps | Fora: largura acima de 2560 |
| 2560x1280 a 60 fps | Fora: 768.000 blocos/s |
| 2560x1280 a 30 fps | Dentro |
| 1920x960 a 30 fps | Dentro |
| 1440x720 a 120 fps | Dentro, no limite |

Os limites são declarados pelo fabricante e podem ser conservadores. O M2 testa um arquivo real antes de concluir.

**Outras consequências:**

- **Sem giroscópio no tablet:** ViewFinder (reenquadrar movendo o aparelho) não funciona aqui. Isso não afeta o `vrot`, que vem da câmera.
- **Samsung Accessory Service:** versão 3.1.96.61123, instalada pela Play Store, `targetSdk` 35. Ainda nunca executou: está parado, sem permissão `BLUETOOTH_CONNECT`, e a câmera não está pareada. Instalar não prova que funciona com a câmera.
- **OTG:** o tablet declara USB host, então o leitor de cartão é viável.
- **ADB por Wi-Fi cai ao entrar na rede da câmera:** usar ADB por USB nos testes de conexão, ou logs salvos no tablet.
- **PC:** tem cabo e Wi-Fi, então fica online durante a sonda. O script precisa usar o adaptador Wi-Fi explicitamente, sem depender da rota padrão.

## 12. Capacidade total da câmera

Alvo atual no tablet: **2560x1280 a 30 fps**. O app não pode ficar preso a esse alvo: um aparelho mais capaz deve usar todos os modos sem mudança de código.

**Modos de vídeo do R210** (`15_r210_port.md`; confirmar nas amostras):

| Modo | Lentes | Tratamento |
|---|---|---|
| 4096x2048 a 24 fps | Duas | Stitching |
| 2880x1440 a 30 fps | Duas | Stitching |
| 2560x1280 a 60 e 30 fps | Duas | Stitching |
| 1920x960 a 30 fps | Duas | Stitching |
| 1440x720 a 120 fps | Duas | Stitching; câmera lenta depois |
| 1920x1080 a 60 e 30 fps | Uma | Vídeo plano, sem stitching |
| 1280x720 a 30 fps | Uma | Vídeo plano, sem stitching |

Fotos de duas lentes: 5792x2896 e 5472x2736, conforme o `AGENTS.md`. Live view: 2560x1280 fixo no R210, limitado pela câmera.

**Regras de implementação:**

1. **Nada fixo no código.** Modo vem do arquivo e da câmera; limites vêm de `MediaCodecList`, `VideoCapabilities` e `GL_MAX_TEXTURE_SIZE`, lidos em tempo de execução.
2. **Política em função pura e testada.** Entrada: modo do arquivo e limites do aparelho. Saída: processar, processar com saída reduzida, ou guardar para depois. Reduzir a saída não resolve decodificador insuficiente.
3. **Original sempre preservado.** Um 4K que o aparelho não processa continua importado, visível e copiável, com aviso claro, e fica pronto para um aparelho melhor.
4. **Captura separada de processamento.** O app permite gravar em qualquer modo. Se o aparelho não processar o modo escolhido, avisa antes de gravar, sem bloquear.
5. **Padrão por aparelho.** O padrão é o maior modo processável: 2560x1280 a 30 fps neste tablet.
6. **Velocidade desacoplada do tempo real.** Processamento pode ser mais lento que a duração do vídeo, e é o que permite 60 fps caso o limite de blocos por segundo impeça apenas o tempo real. Confirmar no M2.

**Validação de 4K:** o tablet só testa o caminho de recusa, se ele for confirmado. O processamento completo em 4K exige um aparelho capaz, ainda não definido.

## 13. Resultados da sonda (M1)

### Modo "Connect to iOS", 2026-11-06, firmware original, sem Bluetooth

- **Rede:** SSID `Gear 360(XXXX)`, câmera em `192.168.43.1`, canal 6, senha na tela da câmera.
- **Portas:** 7676, 7679 e 9001 abertas; 80 fechada (sem OSC nesse modo).
- **Descoberta:** SSDP `M-SEARCH` retorna `http://192.168.43.1:7676/smp_2_`.
- **Descrição UPnP:** o primeiro GET simples em `/smp_2_` retornou `503` nas duas primeiras execuções; com `User-Agent: SEC_RVF_ML_…` e `Access-Method: manual`, retornou `200`. Depois disso, GET simples também retornou `200`. Não se sabe se a causa é o cabeçalho, o tempo desde o início ou uma mudança de estado. O cliente deve enviar esses cabeçalhos e tentar de novo em caso de `503`.
- **Ações declaradas:** 76 em ContentDirectory (`/smp_3_`) e 3 em ConnectionManager (`/smp_6_`). Declarar não prova funcionar: nenhum SOAP foi enviado ainda.

### Classificação inicial das ações

Baseada só nos nomes. Argumentos e efeitos devem ser conferidos no SCPD antes do primeiro envio de cada uma.

| Grupo | Ações | Regra |
|---|---|---|
| **Bloqueadas** | `X_SetFormat`, `X_SetReset`, `X_LogDumpOperation`, `X_SetSDKSupport`, `X_SetControllerInformation`, `ImportResource`, `X_SyncList`, `SetTotalCopyItems`, `X_MakePlaybackFile` | Nunca enviar |
| Consulta (primeiro teste) | `GetSystemUpdateID`, `GetSearchCapabilities`, `GetSortCapabilities`, `X_GetStorage`, `X_GetRemainStorageInfo`, `X_GetDeviceOpertaionMode`, `X_GetFeatureList`, `X_GetSettingInformation`, `GetIP`, `Browse` | Uma por vez, com checagem de saúde entre elas (`tools/stock_probe/soap_query.py`) |
| Parecem consulta, mas não são | `X_GetDeviceStatus` (recebe `StateEvent`, devolve só código de erro); `GetInfomation` (dispara `EVT_RVF_VIEWERFOUND` na interface da câmera, em log de C200) | Fora da consulta; avaliar no M4 |
| Captura | `Shot`, `X_ShotOnly`, `StartRecord`, `StopRecord`, `X_IntervalShot`, `X_TimerlOperation` | M3 e M4 |
| Live view | `SetOperationState`, `SetStreamQuality`, `StopStreaming`, `X_PauseStreaming` | M4 |
| Apagar | `X_DeleteFile`, `X_DeleteFileByID` | Só com confirmação do usuário, e com cartão copiado |
| Ajustes | Demais `Set*` e `X_Set*` | Um por vez, conforme M4 |

A lista não inclui ação de atualização de firmware: esse fluxo passa pelo Bluetooth (`fw-download`, `install-req`), que o app não usará.

### Consultas SOAP, 2026-11-06

Primeiro uso de POST: 10 ações, `POST /smp_4_`, com a câmera respondendo depois de cada uma. O dono confirmou que a tela da câmera não mudou durante as consultas. Logo, `DeviceStatus` = `LiveView` não veio acompanhado de mudança visível.

| Ação | Resultado |
|---|---|
| `X_GetSettingInformation` | Firmware `R210GLU0ARB2_0.81` (a última versão), bateria 82%, sem carregar, `DeviceStatus` = `LiveView`, ajustes e valores atuais |
| `X_GetStorage` | Total 60935, livre 60928, usado 7 (provavelmente MB) |
| `X_GetRemainStorageInfo` | `AVAILSHOTS` 0, `RemainRecTime` 31536000, cartão `External`. Valores de restante parecem pouco úteis no modo atual |
| `X_GetDeviceOpertaionMode` | `Home` |
| `X_GetFeatureList` | `samsung.com_BASICVIEW`, contêiner 8 |
| `Browse` da raiz | Um contêiner: `AllMedia`, id 8 |
| `GetSystemUpdateID`, `GetSearchCapabilities`, `GetSortCapabilities` | `0`, `NULL`, `NULL` |
| `GetIP` | `500`, erro UPnP 401 `Invalid Action`: declarada, mas não implementada |

**Consequências:**

- **Declarar não basta:** `GetIP` prova que cada ação precisa de teste.
- **Capacidades dependem do modo:** em Looping Video, a resolução dupla lista só `2560x1280`. O app deve reler `X_GetSettingInformation` após cada troca de modo, em vez de assumir a tabela da seção 12.
- **Modos:** `Video`, `Photo`, `Time lapse`, `Looping Video` e `Landscape HDR`. O último não estava na seção 12.
- **Ajustes úteis:** EV de -3 a +3, ISO máximo 400 a 1600, WB, nitidez, HDR, corte de vento, lentes, timer, intervalo e tempo de gravação.
- **`ResetValue`:** confirma `X_SetReset` com `All`, `Camera` e `Connection`. Segue bloqueada.
- **Bateria e firmware:** `X_GetSettingInformation` basta para a tela inicial, sem usar `GetInfomation`.

### Primeiro `Shot`, 2026-11-07: serviço travou

Um único `Shot` com `GPSINFO` vazio, às 00:26, em modo Photo. Sem resposta em 30 s. Depois disso, Wi-Fi, ping e portas 7676, 7679 e 9001 seguem ativos, mas `/smp_2_` não responde, e as consultas também falham. **Nenhum outro comando foi enviado.** A tela da câmera seguiu normal, e o serviço não voltou sozinho em cerca de 3 min. Depois de desligar e ligar pelo botão, a câmera voltou e tirou foto pelo próprio botão (relato do dono). Falta saber se a foto remota foi tirada.

**Depois do reinício e de reativar Connect to iOS (00:31-00:32):** Wi-Fi, ping e portas 7676, 7679 e 9001 ativos, mas GET em `/smp_2_` deu timeout e SSDP não teve resposta. O serviço de controle **ainda não foi confirmado de volta**. Sem comandos enviados além de leitura.

**`Shot` fica suspenso** até entender a causa.

**Sequência real do app oficial** (log de um C200 em `gear360reveng`; falta confirmar no R210):

1. `GET /smp_2_`: o primeiro responde `503`, o segundo `200`. Portanto o `503` é parte do fluxo normal.
2. `GET /smp_3_` e listas de ações.
3. `SUBSCRIBE` com um endereço de retorno no cliente (`http://<cliente>:8059/evetSub`), gerando `EVT_RVF_ML_SUBSCRIBE_START`.
4. `SetOperationState` com `changeToRVF`.
5. `GetInfomation`, gerando `EVT_RVF_VIEWERFOUND`.
6. Só então os comandos: `StartRecord`, `StopRecord`, `SetStreamQuality` etc.
7. `UNSUBSCRIBE` encerra (`EVT_RVF_ML_APP_CLOSE`).

Nosso `Shot` pulou os passos 3 a 5, o que é agora a hipótese principal do travamento. Não há `Shot` nesse log. Hipóteses secundárias: `GPSINFO` vazio; falta de quem receba eventos. O passo 3 pode também explicar o `Browse` vazio, junto com `changeToML`. Próximos testes, cada um com aprovação: 3, depois 4, depois 5, com checagem de saúde e `UNSUBSCRIBE` ao final.

`X_SetControllerInformation` devolve `FotaUrlValue`, um campo de atualização de firmware: segue bloqueada.

### `SUBSCRIBE`, 2026-11-07: funciona no R210

Um único `SUBSCRIBE` em `/smp_5_` e um `UNSUBSCRIBE`, com `tools/stock_probe/session_step.py --send` (relatório local `session-subscribe-20261107-151510.json`).

- `SUBSCRIBE`: `200`, com SID e `TIMEOUT: Second-300` aceito.
- `NOTIFY` chegou ao PC: um, `SEQ 0`, com `OperationState=IDLE`, os demais campos `NULL`. Portanto o firewall deixou passar e o passo 3 da sequência vale no R210.
- Eventos declarados incluem `RVF_LAST_PHOTO_URL_VALUE`, `RVF_BUTTON_ACTION`, `RVF_BATTERY_LEVEL_VALUE` e `RVF_DEVICE_ERROR_STATUS`.
- `/smp_2_` respondeu durante a assinatura.
- `UNSUBSCRIBE`: `200`. Logo depois, 7676, 7679 e 9001 fecharam por cerca de 1 min (falhas até 15:16:06, abertas às 15:16:24), com o Wi-Fi conectado. Voltaram sozinhas, e `/smp_2_` respondeu. Causa não confirmada; o evento `EVT_RVF_ML_APP_CLOSE` é a hipótese. O app deve esperar esse intervalo depois de encerrar a sessão.

No log da C200, `UNSUBSCRIBE` (581,36 s) gera `EVT_RVF_ML_APP_CLOSE` (582,27 s), seguido de troca de modo do Wi-Fi. O log não tem `changeToML` em nenhum ponto: lá, o encerramento é só o `UNSUBSCRIBE`. Falta confirmar no R210.

Na C200, `changeToRVF` responde em milissegundos, sai do menu da tela da câmera (`E_SLCD_EXCEPT_EVENT_EXIT_MENU`) e gera `SYSEVT_CAMERA_RVF_ML_CHANGE_DONE`. O stream só começa com um GET próprio.

### `changeToRVF`, 2026-11-07: funciona no R210

Um único `SetOperationState` com `changeToRVF`, 3 s depois do `SUBSCRIBE`, com a câmera no carregador e em modo Video (`session_step.py --step rvf --send`; relatório local `session-rvf-20261107-152141.json`).

- Resposta: `200` em 0,04 s, com `ChargingStatus=Erase` e `DeviceName=smr210`.
- `/smp_2_` respondeu 15 s depois do comando.
- `NOTIFY`: 5. O primeiro traz `OperationState=IDLE` e bateria 45; os demais, só bateria (46) e `RVF_CHARGING_STATUS`. Nenhum `NOTIFY` mudou `OperationState`.
- `RVF_CHARGING_STATUS` alternou entre `Erase` e `Off` no carregador, enquanto a bateria subia de 45 para 47. Significado não confirmado; parece só estado de carga, apesar do nome. Espaço livre (57378) e usado (3557) não mudaram.
- `UNSUBSCRIBE`: conexão fechada sem resposta (`RemoteDisconnected`). A câmera respondeu de novo depois de 50 s. Sem confirmação de que a assinatura foi encerrada; ela expira em 300 s.
- Estado depois: igual ao de antes, exceto bateria. `DeviceStatus` já era `LiveView` antes do comando, então não indica o efeito dele.

Ressalva sobre o log da C200: a câmera estava gravando vídeo durante aquela sessão (tempo de gravação 274 s logo depois de `GetInfomation`). Logo, ele não mostra o comportamento em repouso.

`GPSINFO`: no APK, `Shot` não preenche esse argumento no método do disparo, o que enfraquece a hipótese de que `GPSINFO` vazio travou o `Shot`. `GetInfomation` o preenche no APK, mas a lista de ações do R210 não declara entrada nessa ação.

Na C200, `GetInfomation` gera `EVT_RVF_VIEWERFOUND` e `SYSEVT_CAMERA_SET_GPS_INFO`.

### `GetInfomation`, 2026-11-07: funciona no R210

Um único `GetInfomation`, sem argumentos, 3 s depois de `changeToRVF`, em modo Video e no carregador (`session_step.py --step info --send`; relatório local `session-info-20261107-152603.json`). Resposta: `200` em 0,11 s, 6435 bytes.

- **Stream**, porta 7679: `livestream_high.avi`, `livestream_recording.avi`, `livestream_bc_high.avi`, `livestream_bc_recording.avi`, `livestream_gearvr.avi`. Não abertos ainda.
- **Calibração ao vivo:** `CalData` traz 20 inteiros de 64 bits, com os bits de `double`. Os campos 1 a 19 são idênticos aos do `opax` de um MP4 da câmera. Portanto o app pode obter a calibração sem baixar arquivo.
- **Última mídia e contagem:** `LastFileURL` (`http://192.168.43.1:9001/mnt/mmc/DCIM/100PHOTO/360_0305.MP4`), `FileCount=19`, `RemainRecTime=15913`, `AVAILSHOTS=0` em modo Video.
- **Opções e valores atuais:** modos, resoluções (duas lentes: até 4096x2048@24, 2560x1280@60, 1440x720@120), EV, ISO, WB, bipe, LED, desligamento automático (atual 5min), HDR, nitidez, timer, lente.
- `OpaiData` começa com um identificador em texto; não copiar para documentos públicos.
- Antes do comando, `/smp_2_` respondeu. `NOTIFY`: 2, sem mudança de `OperationState`. Estado depois igual, exceto bateria; espaço não mudou.
- `UNSUBSCRIBE`: de novo `RemoteDisconnected`, nas duas sessões com `changeToRVF`, e `200` na sessão só com `SUBSCRIBE`. Câmera voltou em 50 s e 79,9 s.

### Stream ao vivo, 2026-11-07: funciona no R210

Um único `GET /livestream_high.avi` na porta 7679, depois de `SUBSCRIBE`, `changeToRVF` e `GetInfomation`, lido por 5 s e fechado sem `StopStreaming` (`session_step.py --step stream --send`; relatório local `session-stream-20261107-153126.json`). O script descartou o vídeo: só contagens.

- Resposta: `206`, `video/x-avi`, primeiro byte em 0,91 s, cabeçalho `TTTS`.
- `VID0`: **1280x640**, não 2560x1280. Taxa: 11,1 Mbps (6.967.842 bytes em 5 s). Câmera em modo Video.
- Marcadores: `00VD` 76 vezes (cerca de 15 por segundo). **Nenhum `00AU` nem `00VR`** nesses 5 s. Portanto, este stream não confirma áudio nem giroscópio ao vivo; a seção 3.2 do `AGENTS.md` fica em aberto para o R210. Outros endereços ainda não foram testados.
- A contagem é por busca de marcador, sem interpretar os blocos.
- Imagem não decodificada; `LiveViewDecoder` ainda não foi testado com este stream.
- Espaço usado e livre não mudaram. `/smp_2_` respondeu antes do `UNSUBSCRIBE`, que deu de novo `RemoteDisconnected`.
- Câmera voltou em 106,8 s. Tempos nas sessões com `changeToRVF`: 50 s, 79,9 s, 106,8 s. Crescem, mas cada sessão fez mais que a anterior e são só três medidas.

**Formato do stream, 2026-11-07 (relatório local `session-stream-20261107-194253.json`):** cabeçalho `TTTS` de 124 bytes até `movi`; blocos `id(4) tamanho(4, BE) timestamp(8, BE)`. Cada `00VD` traz **um JPEG** (`ffd8ffdb...`, cerca de 110 KB), **não HEVC**. Timestamps de 8008 em 8008 (relógio de 120 kHz), ou seja, 14,985 fps. Foi por isso que o primeiro teste no tablet ficou em tela preta: `LiveViewDecoder` esperava HEVC. Agora ele desenha JPEG na `Surface`. Câmera voltou em 42,9 s.

**Preview no tablet, 2026-11-07: funciona.** Com `StockClient.openLive` e decodificação de JPEG, a imagem apareceu no Lenovo TB361FU. Atraso de cerca de 2 s e costura razoável em 360, segundo o dono. Depois de parar o preview, a câmera voltou a responder sozinha. Log do app ainda não lido (tablet fora do adb). 

**Medição do atraso, 2026-11-07 19:55 (log do app, 37 s de preview):**

- Tablet decodifica e desenha cada JPEG em 10 a 15 ms, e nunca havia outro quadro inteiro esperando (0 pulados). **O tablet não é a causa.**
- Chegam cerca de 9,6 quadros/s (41 a 52 a cada 5 s), contra 15 no PC.
- O desvio entre chegada e relógio da câmera sobe a 3,2 s nos primeiros 6 s e depois oscila entre 1,7 e 2,8 s, sem crescer. Portanto, uma fila de cerca de 2 s se forma no começo, antes do tablet (na câmera ou no Wi-Fi), e fica estável. Como o desvio não cresce com 9,6 quadros/s, a câmera parece descartar quadros na origem.
- **`livestream_bc_high.avi`, 2026-11-07 20:08, pelo PC (relatório local `session-stream-20261107-200828.json`):** `206`, `TTTS`, **1440x720**, cerca de 127 KB por quadro, 15,1 quadros/s (15,4 Mbps). É **mais pesado** que o `high`, então não reduz o atraso. Mas traz **áudio e giroscópio**: 235 `00AU` e 76 `00VR` em 5 s. O primeiro bloco é `00VR` com 32 bytes (pares racionais, `0/10`...), não 24; depois dele, a leitura `id/tamanho/timestamp` desalinha e o JPEG começa 8 bytes antes do esperado. Formato desse stream ainda não entendido, e `LiveViewDecoder` não o lê. Câmera voltou em 29,8 s.
- `GetInfomation` declara só `High` em `StreamQuality`, então `SetStreamQuality` não deve oferecer opção mais leve.
- No PC, o `high` chega a 15 quadros/s; no tablet, 9,6. A diferença aponta para o Wi-Fi do tablet. Primeira tentativa, sem comando novo à câmera: travar o Wi-Fi do tablet em modo de baixa latência (`WIFI_MODE_FULL_LOW_LATENCY`) enquanto o preview está aberto.
- **Com Wi-Fi em baixa latência, 2026-11-07 20:13:** nos primeiros 15 s, 15 quadros/s e desvio estável em -60 ms, ou seja, sem fila. Depois a vazão caiu (57, 12, 22 e 34 quadros a cada 5 s), o desvio subiu até 8,2 s, e a rede da câmera caiu às 20:14:23. A queda da rede às 20:14:23 foi o fim da sessão: o dono parou o preview, e o `UNSUBSCRIBE` derruba a rede da câmera por alguns segundos. A queda de vazão nos 20 s anteriores segue sem causa: Wi-Fi da câmera instável (como nas quedas do PC), interferência, distância ou aquecimento.
- Hipótese: a vazão do Wi-Fi entre câmera e tablet não dá conta de cerca de 110 KB por quadro. Para reduzir, testar um stream mais leve (`livestream_bc_high.avi`, 1280x720 declarado, ou `SetStreamQuality`), cada um como comando novo, com aprovação.

Relato do dono sobre esses testes: só a conexão da câmera com o PC caiu, várias vezes. Causa não confirmada; o relato não detalha tela nem LED.

**Modo Street View (OSC), plano de teste:** pela análise de firmware do Revive360 (`15_r210_port.md` §7, nunca testada em câmera), menu Connect → "Connect to Street View" sobe uma rede `<modelo>(<4 hex>).OSC` com senha de 8 dígitos mostrada na tela e um servidor OSC v1 na porta 80. Comandos declarados: `startSession`, `closeSession`, `takePicture`, `listImages`, `getImage`, `getMetadata`, `delete`, `getOptions`, `setOptions` (ISO, obturador, WB, HDR, bateria, espaço). Só fotos. **Passo 1 feito em 2026-11-07 20:35 (relatório local `streetview-20261107-203553.json`):** rede `Gear 360(7F97).OSC`, câmera em `192.168.43.1`. Só a porta 80 abre (7676, 7679 e 9001 fechadas, sem SSDP). `GET /osc/info` dá `200` com fabricante, modelo `GEAR 360`, firmware `R210GLU0ARB2`, `gps: false`, `gyro: false` e os 5 endpoints OSC. O número de série é real, não o placeholder citado na análise: não copiar para documentos públicos. `GET /osc/state` dá `400`, como esperado (a especificação pede POST). **Passo 2 feito em 2026-11-07 20:37 (`osc_step.py --step options --send`; relatório local `osc-options-20261107-203708.json`):** tudo `200`. `POST /osc/state`: `fingerprint` `ABCD1234`, `sessionId` `SID_0000`, `batteryLevel` 1.0 (no carregador). `camera.startSession`: `SID_0216`, `timeout` 300. `camera.getOptions` com 18 nomes devolveu só 6: `captureMode` `image` (suporte só `image`), `iso` `auto`, `whiteBalance` `auto`, `hdr` `true` e `hdrSupport` `true`. Sem `exposureProgram`, `shutterSpeed`, listas de ISO e WB, `fileFormat`, espaço nem fotos restantes. `camera.closeSession`: `done`. Câmera seguiu respondendo. Conclusão provisória: o OSC do R210 serve para foto remota e HDR, mas não dá controles manuais.

**Passo 3, foto remota, 2026-11-07 20:39: funciona** (`osc_step.py --step picture --send`; relatório local `osc-picture-20261107-203916.json`). Um único `camera.takePicture` na sessão `SID_0216`: `200`, `inProgress`, `id` `1`, `completion` 0. Oito leituras de `/osc/commands/status`, e `done` em 16,2 s com `fileUri` `/mnt/mmc/DCIM/100PHOTO/SAM_0306.JPG`. Sessão fechada, câmera seguiu respondendo. É a **primeira foto remota sem travar a câmera**. O dono confirmou o arquivo no cartão. O arquivo usa prefixo `SAM_` em vez de `360_`. Metadados (lidos sem decodificar a imagem): **5472x2736, já costurado pela câmera**, com XMP `GPano:ProjectionType` `equirectangular`, `UsePanoramaViewer` `True`, `FullPano` 5472x2736. Uma foto normal (`360_0303.JPG`) tem 5792x2896, duas lentes, sem GPano. O MakerNote tem as mesmas tags, inclusive `0x12` (OPAX) e `0x13` (OPAI). Portanto a foto remota pelo OSC já sai pronta para Google Photos e visualizadores 360, mas menor e com a costura da câmera, não a do app. Passo 1 previsto: `probe.py --mode streetview` (TCP 80/7676/7679/9001, GET `/osc/info` e `/osc/state`, SSDP). Passo 2: `camera.startSession` + `camera.getOptions`. Passo 3: `camera.takePicture`.

**Passo 4, download da foto (`camera.getImage`), 2026-11-07 20:59: funciona** (`osc_step.py --step image --send`; relatório local `osc-image-20261107-205920.json`). Um único `POST /osc/commands/execute` com `camera.getImage` e `fileUri` `/mnt/mmc/DCIM/100PHOTO/SAM_0306.JPG`, sem abrir sessão: `200`, `image/jpeg`, 5.764.246 bytes em 0,96 s (~48 Mbps), SHA-256 `84e55e0fb4b091e0ed58873d0bc54f6c0018fd08139ffa13a7f3750af7fa304d` idêntico ao arquivo copiado do cartão. Câmera seguiu respondendo a `/osc/info` no fim.

**Passo 5, foto remota e download no tablet (`OscClient` + `StockCameraScreen`), 2026-11-07 21:51: funciona.** No modo "Street View (OSC)" do app, a tela da câmera mostrou `Capturing` e o tablet tirou, baixou via `camera.getImage` e salvou no álbum `Saved in Gear360Alive: SAM_0307.JPG`. O dono abriu `SAM_0307.JPG` no visualizador 360 do app e confirmou que está ok; o modo saiu de trás de `Features.EXPERIMENTAL`.

Próximos testes, cada um com aprovação: decodificar no tablet; `Shot` dentro da sessão, em modo Photo.

**Preview no tablet, a implementar (nada escrito ainda):**

- `StockClient`: repetir a sequência testada, com 3 s entre os passos: `SUBSCRIBE`, `changeToRVF`, `GetInfomation`, `GET /livestream_high.avi`. Aceitar `206`. Usar enum próprio para as duas ações novas, fora de `StockQuery`.
- `SUBSCRIBE` e `UNSUBSCRIBE` por socket da `Network` da câmera (`network.socketFactory`), porque `HttpURLConnection` recusa esses métodos. O IP de retorno vem de `socket.localAddress`. Escutar `NOTIFY` na porta 8059 e responder `200`.
- Encerrar com `UNSUBSCRIBE` fora da thread principal, sem exigir resposta.
- Tela: `SphereVideoView` e `LiveViewDecoder`, atrás de `Features.EXPERIMENTAL`, com alternância 360/Dual Fisheye e tela sempre ligada. Sem reabrir sozinho. Pausar a leitura de status durante o stream. Avisar que a câmera demora cerca de 2 min para voltar. Ajustar o texto `stock_read_only`.
- Testes em `StockClientTest`: ordem dos comandos, sem SID nada é enviado, falha em `GetInfomation` ainda envia `UNSUBSCRIBE`.

**Serviço de volta (2026-11-07, 15:04):** portas abertas, SSDP com 5 respostas, descrição e listas de ações com `200`, e consultas respondendo. O primeiro GET simples deu `503` de novo, e com os cabeçalhos deu `200`. Não se sabe por que o serviço não voltou no primeiro reinício (00:31) e voltou agora; entre um e outro a câmera foi desligada, e o cartão saiu e voltou.

**Pelo cartão (2026-11-07):** `360_0292.JPG` às 00:15 e `360_0293.JPG` às 00:31, numeração seguida e sem arquivo no horário do `Shot`, por volta de 00:26. Conclusão: o `Shot` **não gerou foto**. A foto das 00:31 bate com o teste pelo botão, e novos arquivos das 12:03 em diante indicam uso normal da câmera.

### Amostras reais do R210, 2026-11-07

Dezesseis arquivos em `100PHOTO/`, fora do git.

| Arquivo | Formato | Observação |
|---|---|---|
| Fotos `360_*` | JPEG 5792x2896 | Firmware `0.81` no EXIF |
| `360_0287`, `360_0295` | HEVC 2560x1280, 29,97 fps, AAC | Cabe no limite do tablet |
| `360_0290`, `360_0297`, `360_0302` | HEVC 4096x2048, 24 fps, AAC | O último tem 8 min e 1,8 GB |
| `360_0294` | HEVC 4096x2048, 10 fps, sem áudio | Time-lapse |
| `SAM_0301` | HEVC 1920x1080, 59,94 fps, AAC | Uma lente, sem `vrot` e sem calibração |

**A câmera grava em HEVC, inclusive 2560x1280.** O teste de 4K anterior usou H.264 sintético; falta repetir com HEVC real.

**Formatos reais, corrigidos em `CameraMetadata`:**

- **`opax` no MP4:** 164 bytes, com 4 bytes big-endian antes do bloco de 160. O código anterior rejeitava.
- **Tag `0x0012` no JPEG:** o offset conta da MakerNote. O parser também lia o início errado do EXIF. Resultado anterior: **calibração nunca aplicada, em foto nem vídeo**, sem aviso.
- **`vrot`:** uma amostra por quadro, e a primeira coluna é zero nas 11.492 amostras de `360_0302`. **Não há yaw**: dá para nivelar o horizonte, mas não estabilizar rotação. Os valores passam de 360 graus, agora normalizados.
- **`acgy`:** bloco separado, ainda não decodificado, possivelmente com acelerômetro e giroscópio brutos. É o candidato para estabilização real no M6.

**Ainda sem validar em imagem:** ordem e sinais dos eixos do `vrot`, e se `DiffX`, `DiffY` e `DiffRoll` devem ser aplicados. O nivelamento fica desligado por padrão até isso ser visto em vídeo real. O teste `RealR210FormatTest` usa as amostras quando elas estão presentes.

### Validação no tablet com arquivos reais, 2026-11-07

- **Foto 5792x2896:** salva em 1,5 s, tamanho integral, com metadados GPano.
- **Vídeo HEVC 2560x1280 a 29,97 fps (5,2 s):** exportado com áudio e metadados esféricos, em cerca de 9 s no total, medição grosseira. Uma cópia de teste recodificada em 2560x1280, de 44 s e cena parada, exportou em cerca de 24 s. Ou seja, a velocidade depende do conteúdo, e 10 min ainda precisam de teste real.
- **Vídeo HEVC 4096x2048:** aviso correto, exportação recusada com `IllegalArgumentException`, sem crash nem arquivo parcial. Confirma que o tablet não processa 4K da câmera. A prévia desse vídeo não abre no tablet por falta de decoder 4K HEVC no SoC: tratado em `VideoStudioScreen.kt` com detecção de erro e card informativo orientando a usar 2560x1280 para edição no tablet ou exportar no PC, mantendo o arquivo original seguro.
- **Foto de uma lente (2304x1296):** dava "Lost contact with the camera". Corrigido e conferido: abre o seletor de apps do sistema.
- **Costura:** fundo do ambiente alinhado à vista, sem medição. Objetos a centímetros das lentes quebram na emenda, por paralaxe, o que é limite físico. Abaixo do horizonte, o `stickFactor` do shader estreita a mistura e deixa a transição com borda dura: avaliar.
- **Cena parada (`360_0303`), avaliação visual:** paredes, porta, móveis e objetos a mais de cerca de 1 m seguem contínuos nas emendas, sem degrau evidente. A mesa brilhante sob a câmera, muito próxima, quebra na emenda e mostra borda vertical. Não houve comparação com e sem calibração nem medição: a calibração parece adequada, sem prova de ganho.
- **Eixos do `vrot`:** a cena parada não os valida. Nivelamento segue desligado.

### Exportação longa, 2026-11-07

Cópia de teste de `360_0305` em HEVC 2560x1280, 21 Mbit/s, com os metadados originais; o tablet não abre o 4K original. Exportada no tablet em cerca de 3 min, para 6 min 48 s de vídeo, cerca de 2,3 vezes o tempo real, com status térmico 0.

Saída H.264 2560x1280: 9.805 quadros, igual à entrada; sem intervalos acima de 0,042 s; vídeo com 408,54 s e áudio com 408,53 s; metadados esféricos presentes; decodificação sem erros no PC; sem crash. Isso substitui o critério de 10 min do M2, com três ressalvas: a duração foi 6 min 48 s, o vídeo foi recodificado, e o sincronismo foi verificado por carimbos de tempo, sem conferir fala ou imagem. A tela seguiu ligada o tempo todo no teste anterior. Implementado em `VideoStudioScreen.kt`: `keepScreenOn` enquanto a tela de exportação estiver aberta, `WakeLock` (`PARTIAL_WAKE_LOCK`) e `TransferService` (Foreground Service) durante `VideoStitcher.export` para manter a CPU ativa e o processo em primeiro plano mesmo se o usuário apagar a tela ou trocar de app.

### Importação e emenda, 2026-11-07

- **Pasta inteira:** `Import folder` entra em subpastas e ignora outros tipos de arquivo. Testado no armazenamento do tablet, com cópias idênticas por MD5. Falta testar com leitor de cartão real.
- **Fora da tela inicial:** `ImportController` pertence ao app. Um arquivo de 1,5 GB foi copiado em cerca de 15 s, com o app em segundo plano, e ficou idêntico por MD5. Sem cancelamento por enquanto.
- **Emenda abaixo do horizonte:** o `stickFactor` foi removido, voltando à mistura original do Revive360. Comparando o mesmo recorte de `360_0303`, a borda vertical na mesa próxima **não mudou de forma visível**, então ela vem principalmente de paralaxe. Sem medição.
- **Emenda do vídeo:** o vídeo usa outro shader (`OesSphereRenderer`), e a remoção do `stickFactor` só havia corrigido a foto. O dono viu problema na costura do vídeo no YouTube. Corrigido com o mesmo método da foto: detalhe em faixa estreita e luz em faixa larga, e sem estreitamento abaixo do horizonte. No mesmo quadro de `360_0295`, a borda vertical dura sumiu nas duas emendas inferiores (avaliação visual). Exportação de 17,6 s em cerca de 16 s, sem perda de quadros. Objetos muito próximos continuam com paralaxe, e não há correção de diferença de exposição entre lentes no vídeo.
- **Google Photos e YouTube:** o dono confirmou o YouTube como 360 com o vídeo anterior (`360_0295_360.mp4`). O novo está em `100PHOTO/exportados/360_0295_360_v2.mp4`. A foto no Google Fotos não foi confirmada explicitamente.
- **Compartilhar:** o dono não encontrou como enviar um arquivo já costurado, porque o app não tinha essa função. Adicionado botão em vídeo já costurado ou plano e ícone em foto já costurada. O dono confirmou que o compartilhamento funcionou (2026-11-07). Falta confirmar se os dois serviços exibem o conteúdo como 360.

### Modelo real da lente, 2026-11-07

O dono avaliou a costura do vídeo como "bem ruim". Causa principal: o modelo equidistante erra cerca de 58 px de 2896 a 90 graus do eixo, em relação ao polinômio do firmware (documentado por `greg-randall/gear360-raw`). Foto e vídeo agora usam esse polinômio, com o limite de cada lente vindo do raio gravado no arquivo.

- **Resultado visual, no mesmo quadro:** em `360_0303`, o ventilador deixou de ser cortado e o pandeiro deixou de duplicar. Em `360_0295`, a cortina na emenda voltou a aparecer inteira. Sem medição objetiva da melhora final. O dono avaliou a costura do vídeo novo como boa (2026-11-07).
- **Rotação entre lentes:** usa metade dos `Diff` de fábrica, com sinais escolhidos por teste em 3 fotos (trechos com correlação acima de 0,8 foram de 4,9% para 13,5%). Evidência modesta, e de uma só unidade.
- **Paralaxe:** diferença radial medida de -3 a +3,9 graus entre lentes, e dependente da distância. Sem correção: objetos próximos seguem quebrando. A opção é uma emenda adaptativa por conteúdo (`--flow` no `gear360-raw`), ainda não avaliada.
- **Faixa escura no vídeo (2026-11-08):** corrigida. A lente física do R210 perde até 66% de luz na borda do círculo (OPAI de fábrica = 2.94x em r=1.0), enquanto o modelo herdado do C200 compensava apenas 24% (exp(0.28)=1.32x). Implementada curva exponencial calibrada do R210 (`strength=1.06`, `power=3.0`), correspondente ao polinômio OPAI da tag `0x0013`, eliminando a queda de brilho na emenda.
- **Repositórios sugeridos:** `hugin_panorama`, `ndevenish/Hugin` e `cuixing158/hugin-panorama` não trouxeram solução direta para o app.
- **ActionDirector (2026-11-07):** as preferências não expõem parâmetros de costura; a única opção 360 é a "Compensação automática de ângulo", para manter a orientação vertical. Ao importar um arquivo duplo, ele grava uma cópia costurada em H.264 4096x2048 na pasta `ActionDirector/2.0/360` do usuário, nomeada `*_Stitch_XHC`. O clipe costurado que já existia mostra uma pessoa menor de idade e foi descartado como amostra. Usar só cenas sem crianças em comparações.
- **Comparação de costura: Câmera OSC vs App Gear360 Alive vs ActionDirector (2026-11-07):**
  - **Resolução:** Câmera OSC (`SAM_0306.JPG`/`SAM_0307.JPG`) e ActionDirector entregam **5472x2736** (crop 2:1 pelo hardware/DSP interno). O App Gear360 Alive entrega resolução integral nativa **5792x2896** (+12% de pixels) sem perdas, usando o polinômio OCamCalib de fábrica (`OPAX` tag `0x0012`) e correção `OPAI` (`0x0013`). Ambas injetam metadados Photosphere / GPano reconhecidos por visualizadores esféricos e Google Fotos.
  - **Qualidade visual:** Nas três opções, a emenda é imperceptível a mais de 1,5 m de distância. A mesa próxima sofre corte por paralaxe óptica física (3 cm entre sensores). O dono validou `SAM_0307.JPG` no visualizador 360 do app no tablet e confirmou que está ok.
- **Layout do stream `livestream_bc_high.avi` e bloco `00VR` (2026-11-07):**
  - O stream broadcast `livestream_bc_high.avi` (1440x720, 15,1 fps, 15,4 Mbps) traz `00VD` (vídeo), `00AU` (áudio) e `00VR` (giroscópio 3DOF).
  - `00VD` e `00AU` usam cabeçalho estendido `id(4) + size(4) + timestamp(8)` = 16B.
  - O bloco `00VR` segue o formato clássico AVI: `id(4: 00VR) + size(4: valor 32) + payload(32B)`, **sem os 8 bytes de timestamp**. Seu payload contém 4 pares racionais `int32` big-endian (`num / den` com `den = 10`), com yaw = 0/10 no R210.
  - Leitores que esperavam timestamp de 8B no `00VR` avançavam 8B a mais, caindo dentro do JPEG seguinte (cujo início `ffd8` começava 8B antes do esperado).
  - O app mantém `livestream_high.avi` (1280x640 JPEG puro a 11 Mbps) como padrão para o preview por ser mais leve e estável.

### Gravação interrompida, 2026-11-07

`360_0305.MP4`: 4096x2048, 6 min 48 s, 1,5 GB, índice completo. O dono viu a câmera desligar, e estimou cerca de 4 min 30 s. Causa não confirmada. `360_0302` chegou a 7 min 59 s. Calor é a hipótese principal: há relatos públicos do mesmo modelo parando em 4K em poucos minutos. Ainda falta repetir o teste observando a tela e a bateria.

### Listagem de arquivos

Depois de gravar amostras, `X_GetStorage` passou de 7 para 68 de espaço usado e o modo passou a `Photo`, então as consultas refletem o estado atual. Mesmo assim, `Browse` do contêiner 8 retornou 0 itens, com `DeviceStatus` = `LiveView`.

Hipótese, ainda não testada: a listagem exige `SetOperationState` com `changeToML`, que muda o estado da câmera e precisa de aprovação própria. Sem listagem por Wi-Fi, as amostras vêm do cartão, por leitor.

### Queda do Wi-Fi

A rede da câmera caiu em menos de 4 min depois de uma sonda, antes de qualquer SOAP. `AutoPowerOffValue` estava em `5min`, o que é a hipótese mais provável, mas não está confirmada. O M3 precisa medir isso, detectar a queda e reconectar.

### Pendências

- Rodar nos modos "Connect to Android" e "Connect to Street View" (baixa prioridade: o caminho principal já responde).
- `Browse` do contêiner 8 e GET de um arquivo na porta 9001. O cartão quase não tem dados, então vale gravar amostras antes.
- Copiar amostras do cartão.
- Os relatórios contêm identificadores da câmera; ficam fora do git. Anonimizar antes de virar fixtures.
