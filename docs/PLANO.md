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
