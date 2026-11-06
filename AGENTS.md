# AGENTS.md — Gear360 Alive

Repositório Oficial: [`walterfr/Gear360Alive`](https://github.com/walterfr/Gear360Alive)

Documento central de arquitetura, inteligência consolidada dos 18 repositórios analisados, decisões técnicas e plano de execução para transformar a Samsung Gear 360 (2016 SM-C200 e 2017 SM-R210) em uma câmera moderna com experiência estilo **Insta360**.

---

## 1. Decisões de Produto e Arquitetura

1. **Câmeras Suportadas (Dia 1):**
   - **Samsung Gear 360 (2016, SM-C200):** Firmware `C200GLU0AQK1`, sensor Sony IMX147 (2× 15MP, fotos 7776×3888, vídeos até 3840×1920@30, RAW 12-bit RGGB).
   - **Samsung Gear 360 (2017, SM-R210):** Firmware `R210GLU0ARB2`, sensor Samsung S5K2M8SX (2× 8.4MP, fotos 5792×2896 / 5472×2736, vídeos até 4096×2048@24, RAW 10-bit GRBG).

2. **Estratégia de Conexão Híbrida (Opção A) & Atualização Embutida (In-App Firmware & OTA):**
   - **Modo Principal (`g360rd` Protocol v1):** Daemon leve em C na câmera (`HTTP :8360` + `BLE GATT d9c70001-...`) com shim `LD_PRELOAD` no `di-camera-app`. Garante BLE wake-up do Wi-Fi sob demanda, controle total de captura/exposição, Live View HEVC (`TTTS`), RAW DNG e pareamento via `CompanionDeviceManager` + `WifiNetworkSpecifier` (mantendo dados móveis 4G/5G ativos no telefone).
   - **Atualizador de Firmware e Preparador de SD Embutido no App (Zero-PC):**
     - **Pacotes Leves Embutidos no APK (`assets/`):** Os pacotes de ativação (`Revive360-activate.zip`, `p8_invalidate.sh`) e os binários do daemon/plugin (`g360rd`, `libg360r_plugin.so` — ~500 KB no total) ficam **embutidos direto no APK**.
     - **Download sob demanda + Verificação SHA-256 para imagens `.bin` (~250 MB):** Como `smc200.bin` (266 MB) e `smr210.bin` (227 MB) são grandes demais para embutir no APK base, o assistente in-app baixa a imagem correspondente da GitHub Release oficial (ou aceita arquivo local), valida o hash SHA-256 e grava no cartão SD (via leitor USB-C OTG / `StorageAccessFramework`) junto com `fwupgrade.txt`.
     - **Atualização OTA via Wi-Fi (Sem tirar o SD da câmera):** Uma vez com o mod ativo, futuras atualizações do daemon (`g360rd`), plugins (`libg360r_plugin.so`) ou até mesmo do firmware `.bin` são enviadas direto pelo app via Wi-Fi (`PUT /api/v1/debug/upload` com rate-limit seguro para o rádio Broadcom + `POST /api/v1/debug/restart`), sem precisar remover o cartão microSD da câmera.
     - **Restauração 1-Click para Firmware Stock:** Opção no assistente para gravar a imagem original (`C200GLU0AQK1` ou `R210GLU0ARB2`) + `fwupgrade.txt` para reverter 100% ao estado de fábrica.
   - **Modo Fallback Zero-Mod (Stock Firmware):** Cliente **Open Spherical Camera (OSC)** (`/osc/info`, `/osc/state`, `/osc/commands/execute` em `192.168.107.1:80` ou `192.168.43.1:80` no modo *Google Street View*) + cliente **UPnP/DLNA** (`:7676` SOAP, `:7679` stream, `:9001` files) para usuários com câmera 100% original sem SD preparado.

3. **Base de Código:**
   - Evolução direta sobre `simoneluconi/Revive360` (GPLv3), enriquecida com a matemática óptica/calibração de fábrica de `greg-randall/gear360-raw` (GPLv3), o parser `VROT`/TTTS de `teccheck/Gear360App` (GPLv3) e o cliente OSC de `arellaeast/gear360-revive` (MIT).

---

## 2. Mapa Técnico dos 18 Repositórios Analisados

| Repositório / Fork | Papel no Projeto | O que Aproveitamos / Lições das Issues & PRs |
| :--- | :--- | :--- |
| **`simoneluconi/Revive360`** | Base principal (App Compose + `g360rd` C daemon) | Arquitetura de rede (`CameraNetwork`, `BleSession`, `CompanionWifi`), `LiveViewDecoder.kt` (HEVC TTTS), `SphereShader` (GLSL ES 3.0 multiband 3 níveis via mipmap), `DngWriter.kt` e adaptadores C200/R210. |
| **`greg-randall/gear360-raw`** | Calibração Óptica EEPROM + Comandos RTOS | Decodificação do `MakerNote 0x0012` (**OPAX**: 15 floats de fábrica — centro/afim por lente + polinômio **OCamCalib** Scaramuzza), `MakerNote 0x0013` (**OPAI**: polinômio de 5ª ordem para lens shading/vinheta), catálogo de 400 comandos `st cap` e limites de exposição. |
| **`teccheck/Gear360App`** | Parser Live Stream `TTTS` + Giroscópio `00VR` | `MediaExtractor.java` e `Extractor.kt` documentam os chunks `00VD` (vídeo), `00AU` (áudio) e **`00VR` (`VrotData`: yaw, pitch, roll em tempo real no stream)**. Issues #1/#2 destacam demanda por RTMP/Webcam e setup fácil. |
| **`arellaeast/gear360-revive`** | Cliente OSC (Open Spherical Camera) | `OscClient.kt` com fallback automático para as 3 variantes de `listFiles` (`camera.listFiles`, `camera.listImages`, `camera._listFiles`) da implementação semi-padrão da Samsung. |
| **`ultramango/gear360pano`** *(+ fork `morihofi`)* | Referência de geometria e metadados 360 | Templates `.pto` para C200/R210 e injeção de tags EXIF/GPano. Issues (#60–#72) provam que stitch de vídeo via extração de frames em disco falha por espaço/tempo — validando nossa decisão de stitch 100% GPU via `MediaCodec`. |
| **`ottokiksmaler/gear360_modding`** *(+ fork `niluss/gear360_gallery_mod`)* | Comandos `st cap capdtm`, `keyscan360` e WebGL | Tabela de `USERDATA` do DRIMe5 (`ISO 100`, `ISOAUTOMAX`, `ISONR OFF`, `LTNR OFF`). O fork `niluss` traz shader WebGL leve para preview de vídeo dual-fisheye sem stitch prévio. Issues #8, #12, #15 pedem uso carregando, shutter speed e live stream. |
| **`vitorio/gear360-2017-mods`** *(+ forks `hasenbalg`, `bluedskim`)* | Intervalômetro / Timelapse em alta resolução | Comportamento do modo OSC vs iOS/Android AP e scripts de timelapse contínuo desconectado. |
| **`ultramango/gear360reveng`** | Engenharia reversa de Firmware SLP e Rede | Formato do container `.bin` SLP (10 chunks: `uImage` kernels, `lzop` ext4 `rootfs`/`opt`, RTOS e swap/snapshot), portas `7676`/`7679`/`9001` e logs reais de handshake. |
| **`KieronQuinn/Gear360_OSS`** & **`sithgundam/Gear360_fw_updater`** | Hashes e Atualização Offline de Firmware | Hashes SHA-256 oficiais (`C200GLU0AQK1`, `R210GLU0ARB2`) e fluxo de update via SD card sem depender do servidor desligado `samsungimaging.com`. |
| **`bilde2910/stitch-gear360`** *(+ fork `tkalfaoglu`)* | Pipeline de áudio + Spatial Media | Preservação de trilha de áudio original e injeção de metadados `Spherical Video` (`st3d`/`svhd`) em MP4. |
| **`aubguillemette/gear360-to-orbit360`** | Átomos `moov/udta` em MP4 | Estrutura de boxes `udta` nos MP4 gerados pela Gear 360 (`opax`, `opai`, `vrot`). |
| **`zinziroge/gear360_view`** & **`MMR1221385/Gear360-Converter`** | Visualização esférica e automação | Referências históricas de projeção esférica e empacotamento. |
| **`Nackos/gear360-magic-camera`** | Conceitos de UI (Apenas referência visual) | Código-fonte analisado é um simulador/mockup frontend (sem implementação real de protocolo da câmera). |
| **`GRBraga/Gear360`**, **`kleberkg/gear360`**, **`fjedfjo/Gear360Connect`** | Repositórios vazios / placeholders | Sem código aplicável. |

---

## 3. Especificações Técnicas Fundamentais (Referência Rápida)

### 3.1 Calibração Óptica de Fábrica (OPAX / OPAI) e Modelo OCamCalib
Cada unidade física da Gear 360 possui calibração de fábrica gravada na EEPROM e copiada em **todas as fotos** (`MakerNote 0x0012` e `0x0013`) e **todos os vídeos MP4** (`moov/udta/opax` e `opai`):
- **SM-R210 (`MakerNote 0x0012` — 160 bytes = 20 `float64` little-endian):**
  - `[0]`: Versão (`int32` = 2)
  - `[1..2]`: `LeftCenterX`, `LeftCenterY` (em escala de 2896 px)
  - `[3..4]`: `RightCenterX`, `RightCenterY`
  - `[5..6]`: `LeftRadius`, `RightRadius`
  - `[7..9]`: `LeftAffine` (`c`, `d`, `e`)
  - `[10..12]`: `RightAffine` (`c`, `d`, `e`)
  - `[13..15]`: `DiffX`, `DiffY`, `DiffRollAngle`
  - **Polinômio OCamCalib (`invpol` na escala 2048):**
    `[971.9375896974, 526.1058092068, -499.094003808, -645.9034011167, -336.1369527907, -65.8206694527]` onde $\theta = \text{atan}(z / \sqrt{x^2 + y^2}) \in [-\pi/2, 0.2]$.
- **SM-C200 (`MakerNote 0x0012` — 160 bytes = 40 `int32`):**
  - Blocos simétricos nos índices `8..20` (Lente 1) e `21..33` (Lente 2).
- **Lens Shading / Vinheta (`OPAI` — `MakerNote 0x0013`):**
  - Polinômio de 5ª ordem em $r$ normalizado ($r=1$ na borda do círculo):
    `c = [1.0, 0.325752, -2.37229, 12.7928, -20.9402, 12.1364]`.

### 3.2 Giroscópio e Estabilização "FlowState" (`vrot` / `00VR`)
- **Em arquivos MP4 (`moov/udta/vrot`):**
  - Header: `u32 size`, `"vrot"`, `u32 0`, `u32 count`
  - Amostras: `count × { s32 a*10, s32 10, s32 b*10, s32 10, s32 c*10, s32 10 }` (racionais big-endian para pitch, roll, yaw com precisão de $0.1^\circ$).
- **No Stream Ao Vivo `TTTS` (chunk `00VR`):**
  - `size (4B BE)` + `timestamp (8B BE)` + 3 pares de `int32` (`num / den`) representando `yaw`, `pitch`, `roll` sincronizados com o clock de 120 kHz do stream.

---

## 4. Roadmap de Implementação (Estilo Insta360)

```
1. [Calibração OPAX/OPAI + Giroscópio `vrot`] -> verify: Testes unitários de parser EXIF/MP4 (`opax`, `opai`, `vrot`) + compilação limpa.
2. [Live View 360° Interativo + Gyro `00VR`] -> verify: `LiveViewDecoder` extrai `00VR` e `LiveViewScreen` renderiza esfera 360° interativa (360 / Front / Rear / Little Planet) a 60fps.
3. [Conexão Híbrida + Atualizador de Firmware / SD Embutido (OTA & OTG)] -> verify: Testes unitários do `OscClient` + `FirmwareUpdater` (validação SHA-256, gravação SAF/OTG e upload OTA via Wi-Fi `/api/v1/debug/upload`).
4. [Player & Stitcher de Vídeo 360° GPU + FlowState (`vrot`)] -> verify: Reprodução de MP4 dual-fisheye em 360° tempo real com estabilização `vrot` e exportação GPU (`MediaCodec` -> GL -> `MediaMuxer`) com metadados Spherical V1/V2.
5. [Insta360 Studio: Reframe com Keyframes (9:16 / 16:9 / 1:1)] -> verify: Timeline de vídeo com interpolação suave de câmera (Yaw/Pitch/Roll/FOV) e exportação MP4 pronto para Reels/TikTok/YouTube.
```

---

## 5. Planejamento de Funções Insta360 em Código Aberto (Fase 2 — Evolução Contínua)

Mapeamento de funções consagradas da linha **Insta360 (X3/X4)** que possuem algoritmos/implementações em código aberto e se encaixam diretamente na nossa arquitetura (`GLES30` + `MediaCodec` + daemon `g360rd`):

| Função Insta360 | Base Open-Source / Algoritmo | Como Implementar no Gear360 Alive | Complexidade |
| :--- | :--- | :--- | :---: |
| **1. ViewFinder (Reframe pelo Giroscópio do Celular)** | Android `SensorManager` (`TYPE_GAME_ROTATION_VECTOR` -> Quarternions -> Euler `yaw/pitch/roll`) | No `VideoStudioScreen`, botão **"Gravar Movimento (ViewFinder)"**: enquanto o vídeo toca, o usuário aponta/gira o celular fisicamente no ar como se estivesse filmando a cena; o app amostra o sensor a 10–15 Hz e grava keyframes suavizados na `KeyframeTimeline`. | Baixa (Zero libs externas) |
| **2. Deep Track 360 (Rastreamento de Objeto/Pessoa por IA)** | **Google MediaPipe / ML Kit Object & Pose Detection** ou **OpenCV CSRT/KCF / ByteTrack** + projeção gnomônica | O usuário toca num alvo (pessoa, carro, pet) na esfera 360°. A cada $N$ frames, o app extrai um recorte retilíneo (256×256 via FBO) centrado na última posição `(yaw, pitch)`, roda o detector TFLite/MediaPipe, calcula o deslocamento angular $(\Delta\text{yaw}, \Delta\text{pitch}, \text{FOV})$ e gera keyframes automáticos mantendo o alvo sempre centralizado. | Média (LiteRT / MediaPipe) |
| **3. TimeShift (Hyperlapse + Speed Ramping)** | Interpolação de curva de tempo $\tau(t)$ em `MediaCodec` + *Frame Blending* (Motion Blur em FBO acumulador GLSL) | Adicionar propriedade `speedMultiplier` (`0.25x` a `16x`) aos `Keyframe`s. No `VideoStitcher`, reescalar `presentationTimeUs` e, em trechos acelerados (`>2x`), acumular múltiplos frames decodificados num FBO com pesos exponenciais para criar *motion blur* cinematográfico sem custo de CPU. | Baixa/Média (100% GPU/MediaCodec) |
| **4. Nadir Patch & Invisible Selfie Stick (Remoção de Tripé/Bastão)** | Inpainting radial polar em GLSL (`lat < -75°`) + Multi-band Laplacian Blending (`SphereShader` / `enblend`) | **Remoção de Bastão:** O bastão reto alinhado entre as duas lentes cai na zona de sobreposição ($\theta \approx \pi/2$); ajustando o raio de corte (`blendStart`/`blendEnd`) assimetricamente no equador inferior ($\text{lat} < -45^\circ$), o bastão desaparece na costura. **Nadir Patch:** Opção para cobrir a base do tripé com espelhamento harmônico do chão ou logo/sticker circular customizável no shader. | Baixa (Apenas GLSL) |
| **5. PureShot HDR (Exposure Fusion) & Starlapse / Night Shot** | **Mertens Exposure Fusion** (contraste + saturação + exposição bem-exposta, sem precisar de tone-mapping complexo) + `st cap` bracketing (`greg-randall/gear360-raw`) | **HDR / PureShot:** O daemon `g360rd` dispara uma sequência rápida de 3 ou 5 fotos (ou DNG RAW 10/12-bit) variando o shutter/EV via `st cap capdtm` (`ISO 100` fixo) e o celular funde em GPU pelo algoritmo de Mertens (pirâmide Laplaciana). **Starlapse:** Modo intervalômetro com exposição longa + empilhamento *Max-Light* (`max(frame_t, acc)`) em GLSL para rastros de estrelas. | Média (GLSL + `g360rd` script) |
| **6. GPS Stats Dashboard (Telemetria HUD no Vídeo)** | `LocationManager` / FusedLocation + GPX/FIT parser + Canvas/OpenGL Overlay Texture | Durante a gravação pelo app (ou importando um arquivo `.gpx` de smartwatch/Strava sincronizado pelo timestamp do vídeo), renderizar velocímetro, altimetria, força G (derivada do próprio `vrot` da câmera!) e mini-mapa de trajeto numa textura RGBA sobreposta no `VideoStitcher`. | Baixa |
| **7. Me Mode / Auto-Frame (Enquadramento Automático por Saliência)** | MediaPipe Selfie/Face Detector em projeção dual-hemisfério | Modo onde a câmera foca automaticamente em quem está segurando o bastão (ou detecta os rostos mais ativos na esfera 360°) e gera cortes automáticos prontos para Reels/Shorts sem edição manual. | Média |
| **8. Live Stream 360° / Modo Webcam OBS (RTMP / RTSP)** | `LiveViewDecoder` (`TTTS`) -> `OesSphereRenderer` -> `MediaCodec` H.264 -> Muxer **RTMP/RTSP** leve (`pedroSG94/RootEncoder` ou socket RTSP nativo) | Atende às issues mais votadas (`teccheck #1`, `gear360_modding #15`): transmite o sinal ao vivo já costurado em 360° (Equirectangular) ou Reframe 16:9 direto para YouTube Live, Twitch ou OBS Studio via Wi-Fi. | Média |

---

## 6. Convenções de Desenvolvimento neste Repositório

- **Stack Android:** Kotlin, Jetpack Compose, OpenGL ES 3.0 (`android.opengl.GLES30`), `MediaCodec` / `MediaMuxer` nativos (zero dependências pesadas de FFmpeg no APK).
- **Testes:** Todo parser binário (`opax`, `vrot`, `TTTS`, `OscClient`, interpolador de keyframes) deve ter teste unitário em `app/src/test/`.
- **Performance GPU:** Jamais decodificar vídeos salvando frames no armazenamento; sempre usar `SurfaceTexture` → FBO OpenGL ES 3.0 → `MediaCodec.createInputSurface()`.
