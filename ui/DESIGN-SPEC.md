# Nuvio DeskRemote — Especificação Visual e Arquitetura UI/UX (Liquid Glass)

- **Projeto:** Nuvio DeskRemote (Aplicativo Android + Companion Desktop Windows tudo-em-1 para Nuvio HTPC + Controle de PC + Espelhamento de Tela)
- **Escopo da Especificação:** Sistema Visual Liquid Glass, Ponte Nativa `PhoneRemote` (Fases 1, 2 e 3), Fluxo de Primeiro Acesso (`OnboardingScreen`), Responsividade Android Multitela (`RemoteScreen.kt` e `TrackpadScreen.kt`) e Janela Desktop Nativa Windows (`Nuvio DeskRemote Companion` em Tauri v2 / WebView2 + Bandeja do Sistema)
- **Alvo Técnico Android:** Kotlin + Jetpack Compose Foundation/UI + `NuvioGlassTheme` (**zero Material 3**, compatibilidade universal sem exigir giroscópio físico, alvos táteis `>= 48.dp` e layouts responsivos de `320.dp` a `480.dp+`)
- **Alvo Técnico Desktop Windows:** Janela Nativa Compacta (Tauri v2 / WebView2 + Menu de Contexto na Bandeja do Sistema, sem abrir abas de navegador)
- **Integração:** Nuvio HTPC v1.16.1 (Ponte nativa `PhoneRemote` porta `47321` + Servidor Companion porta `8787` WebSocket/WebRTC + Descoberta UDP na LAN + Wake-on-LAN + Pareamento por QR Code / PIN de 6 dígitos `LAN_ONLY` + Quick Settings Tile)

---

## 1. Diretriz Fundamental: Separação Estrita de Modos

O aplicativo possui **dois domínios funcionais independentes** e **uma aba de configuração do aplicativo**. É **proibido** misturar controles de um modo dentro do outro.

| Modo / Domínio | Abas | Escopo Exclusivo | Pílula Superior Direita |
| :--- | :--- | :--- | :--- |
| **Modo Nuvio** | `Remote`, `Apps` | Exclusivo do **Nuvio HTPC v1.16.1**: D-pad, Rockers (`VOL` / `CH`), contexto `Navegar \| Player \| Teclado`, Modo Escuro de Cinema OLED (`#000000`), biblioteca `Assistir` (Continuar Assistindo, Sua Biblioteca, Busca na biblioteca/addons, Temporadas e Episódios, Menu de Ações, Playlists e Resumo com IA), biblioteca `Música` (Álbuns, Artistas e Faixas do Álbum) e `Módulos` (`Início`, `Busca`, `Biblioteca`, `Descobrir`, `YouTube`, `Música`, `Live TV`, `Jogos`, `Agenda`, `Tela Cheia`). | Em **`Remote`**: chave segmentada deslizante **`[ Navegar \| Player \| Teclado ]`**. Em **`Apps`**: chave segmentada deslizante **`[ Assistir \| Música \| Módulos ]`**. |
| **Modo PC** | `Trackpad`, `Tela` | Exclusivo para controle do computador Windows: espelhamento de tela (`screenshare`), mouse/trackpad, rolagem, área de transferência (`Colar no PC` / `Copiar do PC`), `Alt+Tab`, `Cortina`, `Foco PC`, `Mudo PC`, `Apagar Tela`, teclado do PC, troca de monitor (`PC 1` / `TV 2`), qualidade, áudio e print. | **Oculta** a chave do Nuvio. Exibe contexto do PC: em `Trackpad`, ações rápidas de topo (`Alt+Tab`, `Cortina`, sem duplicar abaixo); em `Tela`, indicador de monitor ativo (`PC 1 \| TV 2`). |
| **Sistema do App** | `Ajustes` | Conexão com o computador, pareamento por código de 6 dígitos, acesso fora de casa, vibração tátil e qualidade padrão do espelhamento. | **Oculta** tanto os segmentos do Nuvio quanto os do PC; exibe apenas status da conexão à esquerda. |

### 1.1 Mudanças Obrigatórias de Escopo (Auditoria -> Redesign)

1. **Saem da aba `Remote` (`DynamicContextDock`):** `Foco PC`, `Mudo PC`, `Apagar` e o botão redundante `Ajustes` (já acessível na barra inferior fixa).
2. **Saem da aba `Apps`:** `Mudo Windows` e `Apagar Tela` (movidos para o **Modo PC**). Atalhos exclusivos da janela do aplicativo Nuvio (`Trazer Nuvio à Frente` e `Reiniciar Nuvio`) permanecem na sub-aba `Módulos` do **Modo Nuvio**.
3. **Sai da aba `Tela`:** `Play/Pause` (tecla `Space` de mídia Nuvio) removido da barra flutuante do Modo PC.
4. **Unificação de nome:** a aba 2 passa a chamar-se **`Trackpad`** em toda a especificação, código e barra inferior (removendo a divergência `Mouse` vs `Trackpad`).

---

## 2. Sistema Visual: Liquid Glass Real (`NuvioGlassTheme`)

### 2.1 Proibição Absoluta de Material 3

É **proibido** usar `androidx.compose.material3`, tokens `md.sys`, `MaterialTheme`, `Scaffold`, `Slider`, `Switch`, `Icon` ou `Text` do pacote `material3`.

| Uso Antigo (`material3`) | Substituição Obrigatória em `NuvioGlassTheme` |
| :--- | :--- |
| Dependência `androidx.compose.material3:material3` em `build.gradle.kts` | Remover completamente; usar `androidx.compose.foundation`, `androidx.compose.ui`, `androidx.compose.animation` e `material-icons-core/extended` apenas como vetores `ImageVector`. |
| `material3.Text` | `NuvioText` construído sobre `androidx.compose.foundation.text.BasicText` + `NuvioTypography`. |
| `material3.Icon` | `NuvioIcon` construído sobre `androidx.compose.foundation.Image` com `rememberVectorPainter(icon)` e `ColorFilter.tint(tint)`. |
| `material3.Slider` (`MouseScreen.kt`) | `NuvioGlassSlider`: controle customizado em `Canvas` + gesto horizontal, faixa de toque **`48.dp`**, zero Material 3. |
| `material3.Switch` (`SettingsScreen.kt`) | `NuvioGlassSwitch`: switch customizado (`52.dp × 32.dp` visual dentro de área de toque de `48.dp` na linha inteira), respeitando `LocalHapticsEnabled`. |
| `android:Theme.Material.NoActionBar` (`themes.xml`) | Tema base Android enxuto sem atributos M3 (`android:windowBackground`, edge-to-edge sem barra tonal). |

### 2.2 Regra de Camada: Onde Vai Vidro e Onde Nunca Vai Vidro

| Camada | Aplica Liquid Glass? | Elementos Permitidos / Proibidos |
| :--- | :--- | :--- |
| **Camada Funcional Flutuante** | **SIM** | Barra superior flutuante (`NuvioGlassTopBar` com chaves `[ Navegar \| Player \| Teclado ]` em `Remote` e `[ Assistir \| Música \| Módulos ]` em `Apps`), barra inferior de 5 abas (`NuvioGlassDock`), D-pad circular e botão `OK`, rockers de `Volume` e `Canal`, 4 botões satélites do `Remote`, folha inferior `DynamicContextDock`, folhas flutuantes secundárias do Player, do Modo Nuvio e de `Apps` (`PlayerSubtitlesSheet`, `PlayerAudioSheet`, `PlayerSourcesSheet`, `PlayerMusicQueueSheet`, `PlayerSpeedTimerSheet`, `PlayerLyricsSheet`, `NuvioLiveTvSheet`, `NuvioTonightSheet`, `AppsEpisodesSheet`, `AppsActionMenuSheet`, `AppsAlbumTracksSheet` em `GlassVariant.REGULAR`), barra flutuante de teclado `RemoteTextInputBar`, controles flutuantes da aba `Tela`, moldura de leitura da câmera `QrScannerModal` (`GlassVariant.CLEAR` sobre o feed da câmera) e modais flutuantes (`PairPinDialog`). |
| **Camada de Conteúdo, Listas, Cards e Mídia** | **NUNCA** | **Proibido** aplicar vidro em: mini card *Now Playing*, palco e linhas roláveis de letras sincronizadas (`LYRICS`), lista de canais/programas e barras de progresso do espelho `LIVETV`, opções de perguntas e cards de recomendação 2:3 do seletor `TONIGHT`, carrossel `Continuar Assistindo` (cards 16:9 e miniaturas), grade `Sua Biblioteca` (pôsteres 2 colunas), resultados de busca, grades de `Álbuns` (capas quadradas) e `Artistas` (avatares circulares), células internas de episódios, ações de contexto, playlists, bloco de `Resumo com IA` e faixas de álbum dentro das folhas flutuantes, cards roláveis de `Módulos`, cards de seção e linhas/toggles/inputs da aba `Ajustes`, blocos da Central de Controle do Android (`Quick Settings Tile`), área tátil e trilha de scroll do `Trackpad`, container do vídeo na aba `Tela` e tela preta OLED `#000000` do `Modo Cinema`. |

### 2.3 Anatomia Óptica do Vidro (`NuvioGlassTheme`)

Centralizar tokens, variantes, efeitos de GPU e fallbacks em um único arquivo `ui/theme/NuvioGlassTheme.kt`.

1. **Sem cor própria fixa:** remover o tint azul estático `#93C5FD` (`Color(0xFF93C5FD)`). A superfície de vidro **puxa a cor do que está atrás** através do backdrop capturado + blur + saturação.
2. **Camada 1 — Desfoque ao Vivo + Saturação:**
   - Captura da região de fundo por `NuvioGlassBackdrop` (`graphicsLayer` / `RenderNode` offscreen na área sob o componente) com **blur real** e aumento de saturação (`ColorMatrix.setSaturation`).
3. **Camada 2 — Distorção Leve nas Bordas (Lente):**
   - Em **API 33+**, aplicar `RuntimeShader` AGSL na faixa periférica (`10%–14%` próximos ao contorno da forma) simulando refração óptica sutil nas bordas.
4. **Camada 3 — Proteção de Legibilidade (`REGULAR` vs `CLEAR`):**
   - **Variante `GlassVariant.REGULAR` (Padrão):**
     - **Fundo escuro:** tint neutro translúcido (`rgba(22, 25, 34, 0.56)`), blur `22.dp`, `saturate(1.55f)`. Texto `#F5F5F7` (`>= 10:1` de contraste).
     - **Fundo claro:** tint claro translúcido (`rgba(248, 250, 252, 0.72)`), blur `22.dp`, `saturate(1.45f)`. Texto grafite `#0D1117` (`>= 8.5:1` de contraste).
   - **Variante `GlassVariant.CLEAR` (Exclusiva sobre foto ou vídeo rico — aba `Tela`):**
     - **Sobre cena escura:** tint translúcido sutil (`rgba(14, 17, 24, 0.34)`), blur `16.dp`, `saturate(1.60f)`, texto branco `#FFFFFF`.
     - **Sobre fundo/vídeo claro (`isBackdropLight == true`):** soma **camada obrigatória de escurecimento `+35%`** (`rgba(6, 8, 12, 0.35)` extra sobre o véu base, totalizando escudo escuro limpo sob os ícones/rótulos) para manter **contraste WCAG AA (`>= 4.5:1`)** inclusive sobre a barra de tarefas clara do Windows 11 ou janelas brancas abertas.
5. **Camada 4 — Brilho Especular Sutil + Borda Fina Translúcida:**
   - Borda fina de `1.dp` (`Outline` com gradiente diagonal: topo-esquerda luminoso `white 0.34` no escuro / `white 0.80` no claro -> centro translúcido `0.10` -> canto oposto `0.22`).
   - Reflexo especular interno sutil de `4%–10%` no topo da cápsula (sem brilho neon).

### 2.4 Tabela de Fallbacks por Versão de API Android

| Nível Android | Blur ao Vivo (`RenderEffect`) | Lente / Distorção (`RuntimeShader`) | Saturação (`ColorMatrix`) | Superfície e Contraste Garantidos |
| :--- | :--- | :--- | :--- | :--- |
| **API 33+ (Android 13+)** | **Ativo** (`22.dp` regular / `16.dp` clear) | **Ativo** (refração suave nas bordas via AGSL) | **Ativo** (`1.45f–1.60f`) | Vidro óptico completo + `+35%` de escurecimento em `clear` sobre fundo claro. |
| **API 31–32 (Android 12/12L)** | **Ativo** (`20.dp` regular / `16.dp` clear) | **Desativado** (`Build.VERSION.SDK_INT < 33`) | **Ativo** (`1.45f`) | Vidro com blur ao vivo, borda especular e scrim adaptativo `+35%` em `clear` sobre claro, sem shader de lente. |
| **API 26–30 (`API < 31`)** | **Desativado** (`Build.VERSION.SDK_INT < 31`) | **Desativado** | **N/A** (sem captura filtrada) | Superfície semitransparente estruturada legível (`REGULAR`: `0.88` alfa grafite escuro / `0.92` alfa claro; `CLEAR` ativo: `0.68` escuro / `0.78` fundo claro; `CLEAR` repouso: `0.46` escuro / `0.58` claro) + borda especular nítida de `1.dp`. |

> **Regra Crítica de Transparência sobre Vídeo (`GlassVariant.CLEAR` em `Tela`):** Overlays em `GlassVariant.CLEAR` posicionados acima do `VideoStreamViewport` (`SurfaceViewRenderer`) **NUNCA** podem desenhar a `underlayLayer` gravada pelo `NuvioAmbientBackdropStage` (que captura apenas o fundo estático opaco do app `#EEF2F7` / `#090A0D` atrás do player e transforma botões flutuantes em blocos cinza opacos). Overlays sobre vídeo devem compor apenas tint translúcido real de baixa opacidade sobre o próprio stream + borda especular + sombra sutil nos ícones/rótulos.

---

## 3. Paleta de Tokens Semânticos (Sem Cores Fixas no Vidro)

### 3.1 Superfícies de Fundo e Conteúdo (**Não-Vidro**)

| Token | Tema Escuro (Padrão) | Tema Claro | Uso |
| :--- | :--- | :--- | :--- |
| `CanvasBackground` | `#090A0D` -> `#050608` | `#EEF2F7` -> `#E2E8F0` | Fundo raiz da tela e trás das camadas flutuantes |
| `ContentCardSurface` | `#13161E` | `#FFFFFF` | Cards de `Apps`, cartões de seção de `Ajustes`, painel abaixo do vídeo em janela |
| `ContentCellSurface` | `#1A1E28` | `#F4F6FA` | Linhas `ToggleRow`, campos de input estruturados, células dentro da gaveta/dock |
| `ContentBorder` | `rgba(255, 255, 255, 0.10)` | `rgba(15, 23, 42, 0.10)` | Contorno estrutural `1.dp` de cards, campos e células sem vidro |
| `TrackpadSurface` | `#12151D` | `#F8FAFC` | Área tátil do `Trackpad` e trilha dedicada de rolagem |

### 3.2 Hierarquia Tipográfica e Status (Contraste AA/AAA Verificado)

| Token | Tema Escuro | Tema Claro | Contraste Mínimo |
| :--- | :--- | :--- | :--- |
| `TextPrimary` | `#F5F5F7` | `#0D1117` | `>= 10:1` (`AAA`) |
| `TextSecondary` | `#B4B8C2` | `#475569` | `>= 5.8:1` (`AA+`) |
| `TextMuted` | `#8B92A0` | `#526074` | `>= 4.6:1` (`AA` — corrigido em relação ao grafite escuro antigo) |
| `ClearOverlayText` | `#FFFFFF` | `#FFFFFF` (com scrim `+35%`) | `>= 5.5:1` sobre mídia clara ou escura |
| `StatusConnected` | `#34D399` | `#059669` | `>= 4.5:1` |
| `StatusWarning` | `#FBBF24` | `#B45309` | `>= 4.5:1` |
| `StatusError` | `#F87171` | `#DC2626` | `>= 4.5:1` |
| `ContextNavAccent` | `#E5E7EB` (prata óptico) | `#0F172A` | Indicador discreto de modo `Navegar` |
| `ContextPlayerAccent` | `#C4B5FD` (lavanda suave) | `#6D28D9` | Indicador discreto de modo `Player` |
| `ContextSearchAccent` | `#FCD34D` (âmbar suave) | `#B45309` | Indicador discreto de modo `Teclado` |
| `TvRed / Green / Yellow / Blue` | `#F87171` / `#4ADE80` / `#FACC15` / `#60A5FA` | `#DC2626` / `#16A34A` / `#CA8A04` / `#2563EB` | Pastilhas cromáticas TV (dentro da gaveta do `Remote`) |

### 3.3 Tipografia (`NuvioTypography`)

- Família única geométrica limpa (`FontFamily.SansSerif`), sem misturar fontes monoespaçadas ou códigos técnicos nos rótulos dos controles.
- `DisplayKey`: `15.sp` SemiBold (`OK`).
- `KeyLabelPrimary`: `13.sp` Medium (ações primárias e títulos de cartão).
- `KeyLabelCompact`: `12.sp` Medium (rótulos compactos de pílula e barra de mídia).
- `SectionMicroTitle`: `11.sp` SemiBold, `letterSpacing = 0.04.em`, caixa alta (cabeçalhos de seção e cápsula de estado; substitui o nome legado `HudStatusMono`).
- `BottomNavLabel`: `11.sp` Medium/SemiBold (5 abas inferiores).

---

## 4. Movimento, Performance a 60fps e Acessibilidade

### 4.1 Regras Estritas de Movimento (`NuvioMotion`)

- **Morphing Único:** toda troca de aba (`NuvioRemoteRoot`), mudança de contexto do `DynamicContextDock`, expansão da gaveta, exibição da `RemoteTextInputBar`, strip de qualidade, popover `Mais PC` e modal `PairPinDialog` usa **exclusivamente `scale(0.98f -> 1f)` + `fade`**.
- **Duração:** **`250–400ms`** em todas as transições:
  - Entrada (`morphEnter`): `fadeIn(tween(300ms))` + `scaleIn(spring(dampingRatio = 0.85f, stiffness = 340f), initialScale = 0.98f)` (~300–360ms totais).
  - Saída (`morphExit`): `fadeOut(tween(260ms))` + `scaleOut(spring(dampingRatio = 0.88f, stiffness = 380f), targetScale = 0.98f)` (~260ms).
  - **Remover** `EXIT_DURATION_MS = 220`, `tween(180)`, `tween(240)`, `slideInHorizontally` e `scaleIn(0.88f)`.
- **Micro-interação de toque e pílula deslizante:** física `spring` (`dampingRatio = 0.78f..0.84f`, `stiffness = 320f..480f`) aplicada apenas sobre `graphicsLayer { scaleX; scaleY; alpha; translationX }`.
- **Somente GPU (60fps):** **nunca** animar `width`, `height`, `padding` ou `Dp` por frame que provoque relayout; usar `graphicsLayer` para transformações e `opacity`.
- **Redução de Movimento Dinâmica (`LocalReducedMotion`):**
  - Registrar observador (`ContentObserver` em `Settings.Global.ANIMATOR_DURATION_SCALE` + atualização em `ON_RESUME`) para refletir mudanças do sistema em tempo real (sem congelar `remember(context)` no boot).
  - Quando ativa (`reducedMotion == true`): desativar qualquer `scale`, deslocamento espacial, halo pulsante e movimento deslizante (`snap()` / `EnterTransition.None togetherWith ExitTransition.None`).

### 4.2 Ergonomia, Separação Visual vs. Hitbox (`>= 48.dp`), Simetria e Acessibilidade

- **Visual enxuto com hitbox acessível (`48.dp × 48.dp`):** botões e pílulas não precisam parecer blocos pesados de `48–56.dp`. O componente visual fica elegante e compacto (**`28.dp–42.dp`** de altura visual ou **`34–42.dp`** nos círculos/chips), sempre centralizado dentro de uma zona de toque transparente de no mínimo **`48.dp × 48.dp`** (`defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)`) com separação estrita sem sobreposição entre vizinhos.
- **Alinhamento concêntrico e simetria geométrica:** pílulas internas respeitam folga uniforme em relação à cápsula externa (`top = bottom = right = 6.dp` no topo e dock); colunas pareadas (satélites + rockers no `Remote`; botões `Esquerdo` e `Direito` no `Trackpad`; `Sair` + `Orbe` na borda direita de `Tela`) compartilham o mesmo eixo e proporções equivalentes.
- **Semântica e contraste sem jargão:** cada controle expõe `Role` (`Tab`, `Button`, `Switch`), `stateDescription` clara (`Ativado`/`Desativado`, `Cortina ativa`, `Arrastar ativo`), `liveRegion` discreta em feedback de velocidade/status e rótulos enxutos que **nunca sofrem truncamento (`...`)** em larguras de `360.dp`. Overlays translúcidos sobre vídeo usam halo/sombra de texto para leitura instantânea.
- Todos os textos da interface seguem português natural e direto, **sem jargão técnico** (`WS 8787`, `Token`, `120HZ`, `UDP 8766`, `SECURITY §3`, `/api/v1/pair`).

---

## 5. Especificação Detalhada das 5 Abas (Modo Nuvio & Modo PC)

### 5.1 Navegação Global e Topo Flutuante

- **Pílula Superior (`NuvioGlassTopBar`):** altura visual concêntrica **`48.dp`**, `GlassVariant.REGULAR`, formato cápsula `RoundedCornerShape(999.dp)` (`paddingStart = 14.dp, paddingEnd = 6.dp`). Pílulas internas têm altura visual **`36.dp`** (respiro concêntrico uniforme de `6.dp` acima, abaixo e à direita) dentro de zona clicável **`>= 48.dp`**:
  - **Esquerda (`minHeight = 48.dp`):** LED de status + texto amigável (`"Casa (Wi-Fi) • 2 ms"`, `"Remoto • 18 ms"`, `"Conectando…"`, `"Sem conexão"`, `"Toque para conectar"`).
  - **Direita:**
    - Em `Remote` (**Modo Nuvio**): chave segmentada **`[ Navegar | Player | Teclado ]`** com trilha visual **`36.dp`** (`slider` **`32.dp`**) e botões estendidos para **`48.dp`**.
    - Em `Apps` (**Modo Nuvio**): selo neutro discreto **`Modo Nuvio`** (`34.dp`).
    - Em `Trackpad` (**Modo PC**): botões compactos **`Alt+Tab`** + **`Cortina`** (`36.dp` visual dentro de hitbox **`48.dp`**).
    - Em `Tela` janela (**Modo PC**): seletor **`PC 1 | TV 2`** (`36.dp` visual dentro de hitbox **`48.dp`**).
    - Em `Ajustes`: selo neutro **`Pareamento ativo`** (`34.dp`).
- **Dock Inferior de 5 Abas (`NuvioGlassDock`):** cápsula flutuante `GlassVariant.REGULAR`, altura visual **`56.dp`**, `paddingHorizontal = 5.dp`, pílula ativa visual **`44.dp`** (`6.dp` de respiro concêntrico vertical) e slots clicáveis **`weight(1f) × 48.dp`**:
  1. `Remote` (ícone controle) — Modo Nuvio
  2. `Trackpad` (ícone touchpad) — Modo PC *(renomeado de `Mouse`)*
  3. `Tela` (ícone monitor) — Modo PC
  4. `Apps` (ícone grade) — Modo Nuvio
  5. `Ajustes` (ícone engrenagem) — Sistema do App

---

### 5.2 Aba 1: `Remote` (Modo Nuvio HTPC v1.16.1)

Calibrada para caber integralmente em `360.dp × 800.dp` sem rolagem na visão padrão.

#### Zona Central: Cockpit Siri Remote (Vidro `REGULAR`)
- **Satélites superiores (2 botões circulares `42.dp × 42.dp` visuais centralizados em hitbox `48.dp × 48.dp`, `38.dp` com gaveta aberta):** alinhados verticalmente exatamente sobre o centro dos rockers `Volume` e `Canal` (`paddingHorizontal = 1.dp`).
  - Esquerda: `Voltar` (`nav: back` / `Backspace`)
  - Direita: `Início` (`key: H`)
- **Eixo Central (`Row`, largura útil `336.dp`):**
  - **Rocker Esquerdo `Volume` (`50.dp × 170.dp`, `158.dp` compacto, cápsula `25.dp`):**
    - `+` (`key: Up` no Nuvio / `VolumeUp` físico): área tátil `50.dp × 52.dp` (`48.dp` compacto)
    - Separador simétrico `24.dp` acima e abaixo + `Mudo` (`key: M`, visual `36.dp` / `32.dp` compacto, área tátil **`50.dp × 48.dp`**)
    - `−` (`key: Down`): área tátil `50.dp × 52.dp` (`48.dp` compacto)
  - **D-Pad Óptico Circular (`186.dp × 186.dp`, `168.dp` compacto):**
    - Anel externo em vidro `REGULAR` com reflexo especular diagonal e entalhes cardinais sutis (`up`, `down`, `left`, `right`, áreas de `48.dp × 48.dp` sem sobreposição com OK, hold-to-repeat `340ms` + `115ms`).
    - Botão central **`OK`** (`68.dp × 68.dp`, `56.dp` compacto, `nav: ok` / `Enter`) elevado em camada interna de vidro sem duplicar blur pesado.
  - **Rocker Direito `Canal` (`50.dp × 170.dp`, `158.dp` compacto, cápsula `25.dp`, geometria idêntica ao esquerdo):**
    - `▲` (`key: PageUp`): área tátil `50.dp × 52.dp` (`48.dp` compacto)
    - Separador simétrico `24.dp` acima e abaixo + rótulo central **`CH`**
    - `▼` (`key: PageDown`): área tátil `50.dp × 52.dp` (`48.dp` compacto)
- **Satélites inferiores (2 botões circulares `42.dp × 42.dp` visuais em hitbox `48.dp × 48.dp`, alinhados no eixo dos rockers):**
  - Esquerda: `Detalhes` (`key: I`)
  - Direita: `Tela Cheia` (`key: F11`)

#### Zona Inferior: `DynamicContextDock` (Folha Flutuante Vidro `REGULAR`)
Usa `morphEnter` / `morphExit` (`0.98 -> 1 + fade`, `250–400ms`). Conteúdo interno usa células estruturadas sem vidro aninhado, mantendo altura visual enxuta (`34–42.dp`) dentro de hitboxes `48.dp`.

1. **Estado A — `Navegar` (`HOME` / `DETAIL`):**
   - **Alça de expansão (`minHeight = 48.dp`):** `"ACESSO RÁPIDO"` / grabber / `"Todos"` (`expandido: "CENTRAL DO NUVIO"` / `"Recolher"`). Aceita toque ou arraste vertical (`±26.dp`).
   - **Compacto:** 4 botões circulares **`42.dp × 42.dp`** visuais + rótulo abaixo (hitbox `54.dp × 58.dp`): `Busca` (`search`), `Biblioteca` (`library`), `YouTube` (`youtube`), `Live TV` (`live`).
   - **Gaveta Expandida:**
     - **Grade 2×4 (8 módulos Nuvio, células `48.dp` sem vidro aninhado):** `Busca (S)`, `Biblioteca (L)`, `Descobrir (D)`, `YouTube (Y)`, `Música (U)`, `Live TV (V)`, `Jogos (G)`, `Agenda (C)`.
     - **Ação de saída + Barra de Cores TV (`minHeight = 48.dp` hitbox):** pílula `Sair da tela atual` (`38.dp` visual) + 4 pílulas cromáticas TV (`Verm.` -> `S`, `Verde` -> `L`, `Amar.` -> `C`, `Azul` -> `G`, `36.dp` visuais) todas com hitbox **`>= 48.dp`**.
     - **Zero botões de PC** (`Foco PC`, `Mudo PC`, `Apagar`) e **zero botão `Ajustes`**.
2. **Estado B — `Player` (`PLAYER` Nativo via Ponte `PhoneRemote` + fallback `mpv` — Fase 1):**
   - **Adaptação do Cockpit Superior em `360×800dp`:** quando o `DynamicContextDock` está no estado `Player` (ou `Gaveta`), o cockpit superior usa a disposição compacta de 3 colunas (`Voltar` + `Rocker Volume 150.dp` + `Detalhes` à esquerda; `D-Pad 164.dp` ao centro; `Início` + `Rocker Canal 150.dp` + `Tela Cheia` à direita), preservando os 4 satélites com hitbox `48.dp × 48.dp` sem comprimir a folha do Player.
   - **B.1 — Modo Filme / Série (`kind != "music"`):**
     - **Cabeçalho e Ações Dinâmicas de Segmento (`minHeight = 48.dp`):**
       - À esquerda: indicador lavanda `"PLAYER NUVIO"`.
       - À direita: botões contextuais dinâmicos exibidos apenas quando ativos no stream:
         - **`Pular intro`** (`Pular intro` / `Pular recap` / `Pular créditos`, visual `32.dp`, hitbox **`>= 48.dp`**).
         - **`Próx. ep.`** (`Próximo episódio`, visual `32.dp`, hitbox **`>= 48.dp`**, quando disponível).
     - **Mini Card *Now Playing* (`ContentCellSurface` opaca estruturada, ZERO vidro aninhado, `RoundedCornerShape(16.dp)`):**
       - **Linha de Metadados:** miniatura do pôster `2:3` (`34.dp × 46.dp`, `RoundedCornerShape(8.dp)`), título principal (`KeyLabelPrimary`, ex.: `"Andor"`), subtítulo (`KeyShortcutBadge`, ex.: `"S01 E03 · Acerto de Contas"`) e pílula superior direita **`"Termina às 22:34"`** (calculada a partir do tempo restante em filmes/séries).
       - **Barra de Progresso Interativa (`Seek` via `NuvioGlassSlider`):** linha com altura tátil **`48.dp`**, exibindo tempo atual (`18:42`, `tabular-nums`), trilha de seek customizada (`5.dp` visual, thumb `18.dp`, zero Material 3) e tempo total (`52:10`).
     - **Linha de Transporte (`height = 48.dp`, visual `38.dp`, 3 colunas `1fr : 1.45fr : 1fr`):**
       - **`−10s`** (ou `−5s` no fallback) | **`Play / Pause`** (pílula primária destacada) | **`+10s`** (ou `+5s`).
     - **Linha de Volume Fino do Player (`height = 48.dp`):**
       - Botão **`Mudo`** (`48.dp × 48.dp`, visual `34.dp` circular) + **`NuvioGlassSlider` de Volume** (`weight(1f)`, altura tátil `48.dp`, trilha `5.dp`, thumb `18.dp`) + valor percentual tabular (`78%`).
     - **Linha Inferior de 5 Seletores Reais (`height = 48.dp`, 5 células `ContentCellSurface` sem vidro, `minWidth >= 48.dp`, `minHeight = 48.dp`):**
       1. **`Legenda`**: toque rápido cicla legendas (`S`); toque longo (ou toque no seletor quando a ponte nativa está ativa) abre a folha flutuante **`Legendas`**.
       2. **`Áudio`**: abre a folha flutuante **`Faixas de Áudio`**.
       3. **`Fontes`**: abre a folha flutuante **`Fontes / Streams`**.
       4. **`1,0x • Timer`**: abre a folha flutuante **`Velocidade & Sleep Timer`** (`0.75x..2x` e `Desligado..60 min`).
       5. **`Aspecto`**: cicla proporção/zoom do vídeo (`C`).
   - **B.2 — Modo Música (`kind == "music"`):**
     - **Cabeçalho (`minHeight = 48.dp`):** indicador `"MÚSICA NO NUVIO"` à esquerda + botões **`Letra`** (abre a folha flutuante **`Letras Sincronizadas`**, Fase 3), **`Cinema`** e **`1,0x • Timer`** (`32.dp` visuais em hitboxes **`>= 48.dp`**) à direita.
     - **Mini Card *Now Playing* de Música (`ContentCellSurface` opaca, ZERO vidro):**
       - Miniatura quadrada da capa do álbum `1:1` (`42.dp × 42.dp`, `RoundedCornerShape(10.dp)`), título da faixa (`"Instant Crush"`), badge `"YT Music"`, subtítulo (`"Daft Punk · Random Access Memories"`) e barra de seek interativa (`NuvioGlassSlider` com `48.dp` de altura tátil, `02:14` / `05:37`, **sem** rótulo `"Termina às"`).
     - **Linha de Transporte de Música (`height = 48.dp`, visual `38.dp`):** **`Anterior`** | **`Play / Pause`** (destaque primário) | **`Próxima`**.
     - **Linha de Volume Fino do Player (`height = 48.dp`):** botão **`Mudo`** (`48.dp × 48.dp`) + **`NuvioGlassSlider`** (`48.dp`) + percentual (`65%`).
     - **Linha Inferior de 5 Controles de Música (`height = 48.dp`, 5 células `ContentCellSurface` sem vidro, `>= 48.dp × 48.dp`):**
       1. **`Aleatório`** (Shuffle on/off com destaque ativo)
       2. **`Repetir`** (cicla `Off` / `Todos` / `1` com micro-indicador de estado)
       3. **`Curtir`** (ativo quando logado no YouTube Music)
       4. **`Rádio`** (inicia rádio da faixa atual)
       5. **`Fila`** (abre a folha flutuante **`Fila de Música`**)

#### 5.2.1 Folhas Flutuantes Liquid Glass (`GlassVariant.REGULAR`) para Seletores Reais do Player
Todas as folhas abrem sobre a aba `Remote` com `morphEnter` / `morphExit` (`scale 0.98 -> 1 + fade`, `260–300ms`). **Apenas o container externo da folha usa `GlassVariant.REGULAR` (`elevated = true`)**; todos os chips de filtro, células de lista e badges internos usam superfícies opacas estruturadas (`ContentCellSurface` / `ContentCardSurface`, **zero vidro sobre vidro**) e zonas de toque **`>= 48.dp × 48.dp`**:

1. **Folha `Legendas` (`PlayerSubtitlesSheet`):**
   - Cabeçalho com título `"LEGENDAS"`, dica `"Toque rápido no botão Legenda alterna"` e botão fechar (`48.dp × 48.dp`, visual `34.dp`).
   - Faixa de status de addons (`"Addons sincronizados • OpenSubtitles v3"` ou indicador `"Carregando legendas de addons…"`).
   - Lista vertical de células opacas (`minHeight = 52.dp` por linha): `Desativada`, legendas embutidas (`Português (Brasil) · Embutida [Ativa]`, `Inglês · Embutida SDH`) e legendas de addons (`Português (Brasil) · OpenSubtitles`), com selo de seleção claro.
2. **Folha `Faixas de Áudio` (`PlayerAudioSheet`):**
   - Cabeçalho `"FAIXAS DE ÁUDIO"` + botão fechar (`48.dp × 48.dp`).
   - Lista de faixas reais do arquivo em células opacas (`minHeight = 52.dp`), exibindo título (`Inglês · Dolby Atmos 7.1`, `Português (Brasil) · 5.1`, `Inglês · Estéreo 2.0`), badges técnicos (`Atmos`, `E-AC3`, `AC3 5.1`, `Dublado`) e marcação da faixa ativa.
3. **Folha `Fontes / Streams` (`PlayerSourcesSheet`):**
   - Cabeçalho `"FONTES E STREAMS"` (`Ordem original do Nuvio`) + botão fechar (`48.dp × 48.dp`).
   - **Barra de Filtros (`height = 48.dp`):** 4 chips horizontais com hitbox **`>= 48.dp × 48.dp`** (visual `32.dp`, sem vidro): **`Todos`** | **`4K`** | **`HDR`** | **`Dolby Vision`**.
   - **Lista de Streams (células opacas `ContentCellSurface`, `minHeight = 64.dp`):** exibe provedor/título na ordem do Nuvio e linha de badges compactos por célula: resolução (`4K` / `1080p`), faixa dinâmica (`DV` / `HDR10+` / `HDR`), codec (`HEVC` / `AV1`), áudio (`Atmos` / `5.1`), qualidade (`Remux`) e indicador de cache/tamanho (`Cache • 18,4 GB`).
4. **Folha `Fila de Música` (`PlayerMusicQueueSheet`):**
   - Cabeçalho `"FILA DE REPRODUÇÃO"` + botão fechar (`48.dp × 48.dp`).
   - Lista de faixas em células opacas (`ContentCellSurface`, `minHeight = 56.dp`):
     - Faixa atual com selo `"Tocando"`.
     - Faixas futuras com área principal clicável (`weight(1f)`, `minHeight = 48.dp`, toca imediatamente ao tocar) e **2 botões de ação à direita com hitbox `48.dp × 48.dp` cada** (visual `34.dp × 34.dp`): **`Tocar a seguir`** e **`Remover`**.
5. **Folha `Velocidade & Sleep Timer` (`PlayerSpeedTimerSheet`):**
   - Cabeçalho `"VELOCIDADE E SLEEP TIMER"` + botão fechar (`48.dp × 48.dp`).
   - **Velocidade de reprodução:** grade de 5 células estruturadas (`0,75x`, `1,0x`, `1,25x`, `1,5x`, `2,0x`), cada uma com hitbox **`>= 48.dp × 48.dp`** (visual `36.dp`).
   - **Sleep Timer:** grade de 4 células estruturadas (`Desligado`, `15 min`, `30 min`, `60 min`), cada uma com hitbox **`>= 48.dp × 48.dp`** (visual `36.dp`), além de atalho rápido para `Lista de Episódios`.
6. **Folha `Letras Sincronizadas de Música` (`PlayerLyricsSheet` — `LYRICS`, Fase 3):**
   - **Gatilho:** botão discreto **`Letra`** (`minWidth = 48.dp, minHeight = 48.dp`, visual `32.dp`) no cabeçalho do `Remote › Player` quando `kind == "music"`.
   - **Container externo:** `GlassVariant.REGULAR` (`elevated = true`) com título `"LETRAS DA MÚSICA"`, subtítulo `"Rolagem automática sincronizada"` e botão fechar `✕` (`48.dp × 48.dp`).
   - **Seletor de modo da letra (`height = 48.dp`, 2 botões opacos `>= 48.dp`):** **`Sincronizada`** (tempo real linha a linha) | **`Texto simples`** (visão corrida estática quando a faixa não possui marcação de tempo).
   - **Palco da Letra (`ContentCardSurface` 100% opaco escuro/limpo, ZERO vidro na área rolável):**
     - Cabeçalho interno opaco com título da faixa (`"Instant Crush — Daft Punk · Julian Casablancas"`) e badge `"Sincronizada"`.
     - Lista vertical de versos (`minHeight = 48.dp` por verso clicável para saltar para o timestamp):
       - **Linha ativa atual:** destacada em `ContentCellSurface` opaca com borda lateral `ContextPlayerAccent` (`#C4B5FD` no escuro / `#6D28D9` no claro), texto `TextPrimary` em `13.sp` Bold (`700`) e marcador `"♪ Agora"`.
       - **Linhas anteriores e seguintes:** suavizadas em `TextSecondary` / `TextMuted` sem vidro.
   - **Rodapé de Transporte Compacto (`height = 48.dp`):** 3 botões opacos (`>= 48.dp`): **`Anterior`** | **`Play / Pause`** (primário) | **`Próxima`**.
7. **Folha `Espelho do Live TV` (`NuvioLiveTvSheet` — `LIVETV`, Fase 3):**
   - **Gatilho:** sobe automaticamente no **Modo Nuvio** (`Remote` / `Apps`) quando o Nuvio HTPC entra no Live TV (`LIVETV 1 ...`) ou ao tocar em `Live TV`.
   - **Container externo:** `GlassVariant.REGULAR` (`elevated = true`) com cabeçalho exibindo título `"ESPELHO DO LIVE TV"`, subtítulo do estágio (`"Estágio: Canais ao Vivo • Guia rápido"`) e botão fechar `✕` (`48.dp × 48.dp`).
   - **Chips de Estágio / Categoria (`height = 48.dp`, 4 botões opacos `>= 48.dp × 48.dp`):** **`Todos`** | **`Esportes`** | **`Notícias`** | **`Filmes`**.
   - **Lista Opaca de Canais e Programas (`ContentCellSurface`, ZERO vidro nas células, `minHeight = 58.dp`):**
     - Exibe nome do canal + programa atual (`"ESPN HD · Champions League"`, `"GloboNews · Jornal das Dez"`, `"HBO HD · Duna: Parte Dois"`, `"SporTV · Troca de Passes"`), **selo `AO VIVO`** de alto contraste, horário/tempo decorrido e **barra fina de progresso do programa atual (`3.dp`)** na base de cada célula. Toque troca imediatamente o canal no Nuvio.
   - **Rodapé de Ação Rápida (`height = 48.dp`, 3 botões opacos `>= 48.dp`):** **`Canal −`** | **`Guia / EPG`** | **`Canal +`**.
8. **Folha `"O que assistir hoje"` (`NuvioTonightSheet` — `TONIGHT`, Fase 3):**
   - **Gatilho:** sobe automaticamente no **Modo Nuvio** quando o Nuvio abre o seletor *Tonight* (`TONIGHT 1 ...`).
   - **Container externo:** `GlassVariant.REGULAR` (`elevated = true`) com cabeçalho exibindo título `"O QUE ASSISTIR HOJE"`, progresso da rodada (`"Pergunta 2 de 5 • Clima da sessão"`), barra segmentada de progresso e botão fechar `✕` (`48.dp × 48.dp`).
   - **Bloco de Pergunta e Opções Clicáveis (Células 100% opacas `ContentCellSurface`, ZERO vidro, `minHeight = 48.dp`):**
     - Enunciado da pergunta atual (`"Qual é o clima para assistir agora?"`) e grade 2×2 de respostas clicáveis (`Suspense & Sci-Fi`, `Ação Rápida (< 2h)`, `Comédia Leve`, `Surpreenda-me`).
   - **Card de Sugestão com Pôster `2:3` Opaco (`ContentCardSurface`, ZERO vidro):**
     - Pôster `2:3` (`44.dp × 62.dp`), título (`"Severance · 1ª Temporada"`), metadados (`"Série • Sci-Fi / Thriller • 4K DV • 97% match"`) e **motivo (`why`)** em destaque legível (`"Por quê: combina com Andor e seu histórico noturno de sci-fi."`).
   - **Rodapé de Decisão (`height = 48.dp`, 2 botões `>= 48.dp`):** **`Embaralhar`** (`ContentCellSurface` opaca) e **`Escolher e Assistir`** (pílula primária destacada).

#### 5.2.2 Modo Escuro de Cinema (`CinemaDarkModeOverlay` — Fase 2)
- **Entrada pelo `DynamicContextDock` (`Player` na aba `Remote`):** botão discreto **`Cinema`** (visual `32.dp`, hitbox **`>= 48.dp × 48.dp`**) no cabeçalho do `Player` (tanto em vídeo quanto em música).
- **Tela Cheia Preto Absoluto OLED (`#000000`):**
  - Superfície 100% `#000000` pura (**zero vidro, zero blur e zero emissão de fundo**) ocupando a tela inteira (`360×800dp`) para uso em sala escura sem ofuscar a visão.
  - **Topo Discreto (`height = 48.dp`):**
    - Controle de brilho do texto em 3 níveis (`Baixo` — alfa `0.38f`, `Médio` — alfa `0.62f`, `Alto` — alfa `0.86f` sobre `#000000`), cada opção com hitbox **`>= 48.dp × 48.dp`** (visual `30.dp`).
    - Botão de saída **`Sair (1s)`** (hitbox **`>= 48.dp × 48.dp`**, visual `32.dp`).
  - **Indicador Ultra-Discreto de Reprodução:** título (`"Andor · S01 E03 · Acerto de Contas"`), tempo/término (`"18:42 / 52:10 • Termina às 22:34"`) e linha fina de progresso (`2.dp` de espessura).
  - **Superfície Tátil Central com Guia Mínimo de Gestos:**
    - **Toque simples:** `Play / Pause`
    - **Arrastar horizontal (`← / →`):** `Voltar −10s / Avançar +10s (ou trocar faixa)`
    - **Arrastar vertical (`↑ / ↓`):** `Ajustar volume do player`
    - **Segurar por 1s:** `Sair do Modo Cinema`

3. **Estado C — `Teclado` (`SEARCH` — Digitação e Busca por Voz no Nuvio):**
   - Cabeçalho `"DIGITAÇÃO NA BUSCA DO NUVIO"`.
   - **Linha de entrada com Busca por Voz (`height = 48.dp`):**
     - Campo estruturado sem vidro (`SearchFieldBox`, `weight(1f)`, visual `40.dp` dentro de linha `48.dp`).
     - **Botão de Microfone / Busca por Voz (`Voz`):** hitbox **`48.dp × 48.dp`** (visual `40.dp × 40.dp` em `ContentCellSurface`, ícone microfone, aciona `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` nativo do Android e preenche/envia a busca ao Nuvio).
     - **Botão `Teclado`:** hitbox **`>= 48.dp`** (visual `40.dp`, foca e abre o IME do celular).
   - Linha de ações (`38.dp` visual, hitbox `48.dp`): `Limpar`, `Buscar` (`Enter`), `Fechar` (`Escape`).

---

### 5.3 Aba 2: `Trackpad` (Modo PC — Área Ampliada e Simetria)

- **Superfícies Sem Vidro:** Área multitouch do trackpad (`weight(1f)` ocupando **`>= 50%` da altura total da tela (`~62%` do corpo útil entre barras)**), trilha de rolagem lateral (`48.dp × 100%`) e bloco de velocidade usam superfícies estruturadas foscas (`TrackpadSurface` / `ContentCardSurface`), com alvo limpo em claro e escuro.
- **Zero Duplicação de `Alt+Tab` e Barra Única Simétrica (4 colunas iguais):**
  - `Alt+Tab` e `Cortina` ficam **apenas no topo (`NuvioGlassTopBar`)** — removendo a duplicação no corpo que apertava o trackpad nas capturas de dispositivo real.
  - Logo abaixo do topo, **1 única linha simétrica de 4 pílulas verticais** (`44.dp` visuais em hitbox `48.dp`, `gap = 6.dp`, sem linha órfã e **sem truncamento `...`**):
    1. **`Teclado`** (abre `RemoteTextInputBar` flutuante do PC)
    2. **`Arrastar`** (`dragLocked` alternável com estado semântico claro)
    3. **`Mudo PC`** (`window: mute_toggle`)
    4. **`Apagar tela`** (`window: suspend_display`)
- **Área Multitouch Generosa:** subtítulo claro sem truncamento — `"Área tátil do PC"` e dicas em 2 linhas legíveis: `"1 toque: clique esquerdo • 2 dedos: rolar"` / `"2 toques + segurar: arrastar • toque 2 dedos: direito"`.
- **Barra Simétrica de 3 Cliques (`38.dp` visual em hitbox `48.dp`):** proporção estritamente simétrica **`1.35f : 0.90f : 1.35f`** (`Esquerdo`, `Meio`, `Direito` — corrigindo a assimetria `1.35f : 0.85f : 1.25f`).
- **Bloco Compacto de Velocidade do Cursor (Sem Material 3):**
  - Cabeçalho enxuto (`VELOCIDADE DO CURSOR` à esquerda, valor tabular `1,8x` à direita — sem botão solto de `Apagar Tela` competindo no cabeçalho).
  - **`NuvioGlassSlider` customizado** (`48.dp` interativo, trilha `5.dp`, indicador `20.dp`).
  - **Faixa segmentada única de 4 presets (`1,0x | 1,8x | 2,5x | 3,5x`):** trilha visual compacta **`32.dp`** (segmento ativo `28.dp`) centralizada dentro de 4 células `>= 48.dp × 48.dp` sem sobreposição, reduzindo a altura do card e devolvendo espaço nobre ao trackpad.
- **`RemoteTextInputBar` Flutuante (Vidro `REGULAR`):**
  - Teclas especiais (`ESC`, `TAB`, `ALT+TAB`, `WIN`, `CTRL+C`, `CTRL+V`) e campo com altura visual `34–40.dp` dentro de alvos `48.dp`.

---

### 5.4 Aba 3: `Tela` (Modo PC — Baixa Opacidade Real & Simetria)

#### Modo A: Janela (Retrato com Abas Visíveis)
- **Viewport de Mídia (`16:9`):** vídeo puro (**sem vidro**). Botões flutuantes nos cantos superiores **`Tela Cheia`** e **`Paisagem`** usam **Vidro `CLEAR` de baixa opacidade** com visual compacto **`32.dp`** dentro de hitbox transparente **`48.dp × 48.dp`** (cobrindo o mínimo possível do preview).
- **Painel de Controles Abaixo do Vídeo (Sem Vidro — `ContentCardSurface`):**
  - Status (`Ao vivo • Monitor PC 1 • Casa`) + `Reconectar` (`34.dp` visual, `48.dp` hitbox).
  - Botões principais `Abrir Tela Cheia` e `Som PC: Ativo` (`38.dp` visual, `48.dp` hitbox) + grade 3×2 de atalhos do computador (`Colar`, `Copiar`, `Alt+Tab`, `Foco PC`, `Cortina`, `Mudo PC`).

#### Modo B: Tela Cheia (Paisagem/Retrato) — **Baixa Opacidade Real e Simetria**

**Diagnóstico nas capturas reais do usuário (`1000261048.jpg`):**
1. **`Modifier.nuvioGlass` pintava `underlayLayer` opaco do app sobre o `SurfaceViewRenderer`:** como o vídeo fica em camada separada, capturar o fundo `#EEF2F7` do app tornava `CLEAR` blocos cinza 100% opacos.
2. **Opacidade em repouso (`0.72f–0.78f`) e tamanho visual (`48–64.dp`) eram excessivos:** `Orb`, `Sair` e `^ Barra` pareciam manchas cinzas pesadas atrapalhando a leitura da tela.
3. **Assimetria de posicionamento:** orbe solto de um lado e `Sair` do outro, enquanto a pílula central `^ Barra` bloqueava o centro inferior.

**Solução Especificada (Fase A.2):**
1. **Transparência Real + Baixa Opacidade Calibrada:**
   - **Proibido desenhar `underlayLayer` de fundo do app** em qualquer overlay sobre `VideoStreamViewport`.
   - **Estado Repouso (Discreto sobre vídeo):** opacidade de superfície reduzida para **`0.22f–0.28f`** em cenas normais/escuras e **`0.40f–0.44f`** adaptativo em áreas claras/taskbar branca, com borda fina `1.dp` (`alpha 0.22f`) + **drop-shadow sutil nos ícones e textos** garantindo leitura sem criar bloco opaco.
   - **Estado Barra Ativa / Popover:** opacidade moderada **`0.34f–0.40f`** (escuro) ou **`0.56f–0.62f`** (fundo claro adaptativo `+35%`, mantendo a área da tela visível através do vidro!).
2. **Simetria na Coluna Direita + Compactação Visual (`Hitbox 48.dp` invisível):**
   - **Top-Right `Sair da Tela Cheia`:** círculo visual **`34.dp`** (`15.dp` ícone) dentro de hitbox transparente **`48.dp × 48.dp`** em `(top = 6.dp, right = 10.dp)`.
   - **Mid-Right `Orbe Radial`:** círculo visual **`36.dp`** (`16.dp` ícone) dentro de hitbox **`48.dp × 48.dp`** alinhado **no mesmo eixo vertical direito (`right = 10.dp`, centro vertical `50%`)**, mantendo todo o centro e lado esquerdo da tela livres (suporta arraste livre para outra borda se desejado). Menu radial mantém as 8 fatias (`38.dp` visual em `48.dp` hitbox).
   - **Alça `^ Barra` em Repouso:** micro-pílula translúcida discreta (**`28.dp` visual × `86.dp`** centrada dentro de área tátil transparente `96.dp × 48.dp` a `2.dp` da borda inferior).
   - **Barra Slim Expandida (0 cortes):** cápsula externa visual **`40.dp`** de altura (trilha de toque **`48.dp`**) com **6 ações diretas de `32.dp` visuais** (`Recolher ▼`, `Qualidade/Monitor`, `Toque/Trackpad`, `Teclado PC`, `Retrato`, `Mais PC •••`) + popover `Mais PC` em grade 4×3 (`Colar no PC`, `Copiar do PC`, `Print`, `Cortina`, `Arrastar`, `Som PC`, `Alt+Tab`, `Foco PC`, `Mudo PC`, `Tela (F11)`, `ESC`, `Apagar tela`).

---

### 5.5 Aba 4: `Apps` Evoluída (`Assistir | Música | Módulos` — Modo Nuvio Fase 2)

- **Switcher Superior em Pílula (`[ Assistir | Música | Módulos ]`):**
  - Posicionado à direita na `NuvioGlassTopBar` (ou faixa superior da aba `Apps`), com trilha concêntrica de `36.dp` e 3 botões com hitbox **`>= 48.dp × 48.dp`** (`Assistir`, `Música`, `Módulos`).
- **Regra Inegociável de Camada:** **ZERO vidro** em listas, carrosséis, cards `16:9`, pôsteres `2:3`, capas de álbuns `1:1`, avatares de artistas ou células internas. Vidro (`GlassVariant.REGULAR`) aplica-se **exclusivamente** ao topo flutuante, dock inferior de 5 abas e container externo das 3 folhas flutuantes (`Temporadas e Episódios`, `Menu de Ações` e `Faixas do Álbum`).

#### 5.5.1 Sub-aba 1: `Assistir` (Biblioteca, Continuar Assistindo e Busca)
1. **Barra de Busca + Voz + Atualizar (`height = 48.dp`):**
   - Campo estruturado opaco (`ContentCellSurface`, visual `38.dp` em wrapper `48.dp`): placeholder `"Buscar na biblioteca e addons…"`.
   - Botão Microfone **`Voz`** (hitbox **`48.dp × 48.dp`**, visual `38.dp × 38.dp` em `ContentCellSurface`).
   - Botão **`Atualizar`** (hitbox **`48.dp × 48.dp`**, visual `38.dp × 38.dp` em `ContentCellSurface`).
2. **Barra de 5 Chips de Filtro (`height = 48.dp`, 5 alvos `>= 48.dp × 48.dp`, visual `30.dp` opaco sem vidro):**
   - **`Todos`** | **`Filmes`** | **`Séries`** | **`TV`** | **`Não vistos`** (`Não assistidos`).
3. **Seção `CONTINUAR ASSISTINDO` (Carrossel Horizontal 16:9 Opaco):**
   - Cards `16:9` estruturados (`ContentCardSurface`, **sem vidro**, `minHeight >= 48.dp`), com miniatura `16:9`, **barra fina de progresso na base da miniatura** (`3.dp`), título (`"Andor"`, `"O Urso"`) e subtítulo com episódio e tempo restante (ex.: `"S01 E03 · 19m restantes"`, `"S22 E02 · 19m restantes"`).
   - **Interação:** toque simples retoma a reprodução diretamente na TV.
4. **Seção `SUA BIBLIOTECA` (Grade 2 Colunas de Pôsteres Opacos + Contador de Títulos):**
   - Cabeçalho com título `"SUA BIBLIOTECA"` à esquerda e contador `"24 títulos"` à direita.
   - Cards em 2 colunas (`ContentCardSurface` opaco, **sem vidro**), com arte do pôster, **selo discreto de assistido (`✓ Visto` ou temporada)** no canto superior, título, subtítulo (`"Série · 2 temp."` ou `"Filme · 4K DV"`) e botão de menu `•••` (`48.dp × 48.dp` hitbox) para acessibilidade equivalente ao toque longo:
     - **Toque em Filme:** inicia/abre direto na TV.
     - **Toque em Série:** abre a folha flutuante **`Temporadas e Episódios`**.
     - **Segurar (Long-Press) ou tocar em `•••`:** abre a folha flutuante **`Menu de Ações`**.
5. **Estado `Resultados de Busca` (`apps-busca`):**
   - Ao pesquisar um termo (ex.: `"Duna"`), substitui ou filtra o corpo exibindo `"RESULTADOS NA BIBLIOTECA E ADDONS"`, botão `"Limpar"` (`>= 48.dp`) e lista de cards opacos com origem (`"Na Biblioteca"`, `"Cinemeta · Série"`, `"Addon TMDB · Filme"`), ano/qualidade e ação rápida (`>= 48.dp`).

#### 5.5.2 Folhas Flutuantes Liquid Glass (`GlassVariant.REGULAR`) da Sub-aba `Assistir`
1. **Folha `Temporadas e Episódios` (`AppsEpisodesSheet`):**
   - Container externo `GlassVariant.REGULAR` (`elevated = true`); todas as células internas são opacas (`ContentCellSurface`, **sem vidro**).
   - Cabeçalho com título da série (`"Severance"`), metadados (`"2 temporadas • Toque reproduz na TV"`) e botão fechar `✕` (`48.dp × 48.dp`).
   - **Chips de Temporada (`height = 48.dp`):** `T1` (ativa), `T2`, `T3` e botão `Ações •••` (todos com hitbox **`>= 48.dp × 48.dp`**).
   - **Lista de Episódios (`minHeight = 58.dp` por linha opaca):** miniatura `16:9` compacta com barra de progresso na base, número + título (`"E01 · Bom Dia, Inferno"`, `"E02 · Meio Período"`), duração/status (`"56m • Assistido"`, `"49m • 22m restantes"`) e botão `•••` (`48.dp × 48.dp`) / suporte a long-press para abrir o *Menu de Ações*.
2. **Folha `Menu de Ações (Long-Press)` (`AppsActionMenuSheet`) + Sub-estados `Playlists` e `Resumo com IA`:**
   - Container externo `GlassVariant.REGULAR` (`elevated = true`); itens internos 100% opacos (`ContentCellSurface`).
   - Cabeçalho com miniatura, título (`"Andor · S01 E03"`), detalhe (`"Série • 2022 • 4K Dolby Vision"`) e botão fechar `✕` (`48.dp × 48.dp`).
   - **Seletor rápido de sub-estado na folha (`Ações | Playlists | Resumo IA`, cada um `>= 48.dp × 48.dp`):**
     - **Aba `Ações` (5 ações reais do Nuvio, cada célula opaca `minHeight = 48.dp`):**
       1. **`Marcar como assistido`** (`Sincroniza progresso no Nuvio`)
       2. **`Remover da biblioteca`** (ou `Adicionar à biblioteca`)
       3. **`Playlists`** (`Organizar em listas personalizadas`)
       4. **`Resumo com IA`** (`Gerar síntese contextual sem spoilers`)
       5. **`Escolher fonte`** (`Abrir seletor de streams 4K / HDR`)
     - **Sub-estado `Playlists`:** lista opaca de playlists do usuário (`Favoritos Sci-Fi [✓]`, `Ver no Fim de Semana [✓]`, `Maratona 4K HDR [ ]`, `Lista da Família [ ]`, cada linha `minHeight = 48.dp`) + botão primário **`Salvar nas playlists`** (`>= 48.dp`).
     - **Sub-estado `Resumo com IA`:** card opaco estruturado (`ContentCellSurface`) com o resumo gerado pela IA do Nuvio (contexto do episódio/filme sem spoilers, tom e temas) + botões **`Voltar às ações`** e **`Reproduzir na TV`** (`>= 48.dp`).

#### 5.5.3 Sub-aba 2: `Música` (Álbuns, Artistas e Folha `Faixas do Álbum`)
1. **Seção `ÁLBUNS` (Grade 2 Colunas de Capas Quadradas Opacas):**
   - Cards opacos (`ContentCardSurface`, **sem vidro**) com capa `1:1`, **indicador de favorito (`♥`)** no canto superior direito, título do álbum e artista (`"Random Access Memories · Daft Punk"`, `"After Hours · The Weeknd"`). Toque abre a folha flutuante **`Faixas do Álbum`**.
2. **Seção `ARTISTAS` (Avatares Circulares Opacos + Ações Rápidas):**
   - Linhas/cards opacos (`ContentCardSurface`, **sem vidro**) com **avatar circular** (`40.dp`), nome do artista (`"Daft Punk"`, `"The Weeknd"`) e 2 botões de ação rápida com hitbox **`>= 48.dp × 48.dp`** (visual `34.dp`): **`Mix`** (`Mix do artista`) e **`Rádio`**.
3. **Folha Flutuante Liquid Glass — `Faixas do Álbum` (`AppsAlbumTracksSheet`):**
   - Container externo `GlassVariant.REGULAR` (`elevated = true`); células internas opacas (`ContentCellSurface`).
   - Cabeçalho com capa quadrada, título do álbum (`"Random Access Memories"`), artista/ano (`"Daft Punk • 13 faixas"`) e botão fechar `✕` (`48.dp × 48.dp`).
   - **Linha de 3 Ações do Álbum (`height = 48.dp`, 3 botões `>= 48.dp`):** **`Tocar`** (primário) | **`Aleatório`** | **`Na fila`**.
   - **Lista Opaca de Faixas (`minHeight = 48.dp` por faixa):** número, título da faixa, subtítulo e duração tabular (`01. Give Life Back to Music · 04:35`, `02. The Game of Love · 05:22`, `03. Giorgio by Moroder · 09:04`, `04. Instant Crush · 05:37`).

#### 5.5.4 Sub-aba 3: `Módulos` (Atalhos Nuvio v1.16.1 e Janela do Aplicativo)
- Preserva integralmente os cards opacos (`44.dp` visual em hitbox `48.dp`) da Fase 1:
  - **Seção 1 — Reprodução no Nuvio:** `Painel Player`, `Play / Pause`.
  - **Seção 2 — Módulos do Nuvio v1.16.1:** grade 2×5 (`Início`, `Busca`, `Biblioteca`, `Descobrir`, `YouTube`, `Música`, `Live TV`, `Modo Jogo`, `Calendário`, `Tela Cheia`).
  - **Seção 3 — Janela do Nuvio no Computador:** `Trazer à Frente` e `Reiniciar Nuvio`.

---

### 5.6 Aba 5: `Ajustes` (Sistema do App)

- **Zero vidro nos cartões de formulário, linhas de switch e campos de texto:** superfície estruturada limpa (`ContentCardSurface` e `ContentCellSurface`).
- **Zero jargão técnico visível ao usuário:**
  - `WS 8787 / WSS 443` -> `"Modo de conexão: Rede Wi-Fi local"` ou `"Acesso fora de casa"`
  - `UDP 8766` -> `"Busca automática na rede Wi-Fi"`
  - `Token: salvo e autenticado` -> `"Dispositivo pareado e autorizado"`
  - `Bloqueado por Xs (SECURITY §3)` -> `"Muitas tentativas. Aguarde X s para tentar novamente."`
  - `720p30 / 1080p60` -> `"720p • 30"`, `"720p • 60"`, `"1080p • 30"`, `"1080p • 60"`
  - Remover texto promocional técnico sobre nome de design system na UI.
- **Componentes Enxutos em Alvos `>= 48.dp`:**
  - Campo de texto (`Endereço do computador`, visual `40.dp`), botões de ação (visual `38.dp` dentro de wrapper **`48.dp`**), linha de switch `NuvioGlassSwitch` (`Vibração tátil ao tocar`, `50.dp × 30.dp` visual em linha `50.dp`) e seletor de qualidade padrão (trilha visual **`38.dp`**, pílula **`32.dp`** dentro de células `>= 48.dp`).
- **Wake-on-LAN (`Acordar PC` — Fase 2):**
  - Dentro do card opaco `CONEXÃO COM O COMPUTADOR` em `Ajustes`, botão dedicado **`Acordar PC`** (`minHeight = 48.dp`, visual `36.dp` em `ContentCellSurface`, sem exibir jargão como `Magic Packet UDP 9` ou `MAC Address` bruto).
  - **Estado Computador Offline / Em Suspensão:** quando a conexão falha ou o PC está desligado (`"Computador desligado ou em suspensão"`), o card exibe aviso claro com ponto âmbar e destaca o botão primário **`Acordar PC na rede`** (`>= 48.dp`) ao lado de `Tentar reconectar`.
- **Card Discreto de Pareamento da Ponte Nuvio (`CONTROLE DIRETO DO NUVIO` — Fase 1):**
  - Cartão opaco estruturado (`ContentCardSurface`, **zero vidro**) dedicado à conexão nativa com o Nuvio HTPC (*Phone remote*), **sem qualquer jargão técnico** (proibido exibir `TCP 47321`, `PhoneRemote socket`, `token` ou portas):
    - **Estado Vinculado (`Ativo`):** ponto verde + `"Sincronizado com o Nuvio no PC"` + descrição `"Recebe pôster, progresso, legendas, faixas de áudio, fontes e fila de música em tempo real"` + botão discreto `"Vincular novo código do Nuvio"` (`38.dp` visual em hitbox `48.dp`).
    - **Estado Não Vinculado (`Aguardando código`):** ponto âmbar + `"Código de 6 dígitos do Nuvio pendente"` + instrução `"Abra Ajustes > Controle pelo celular no Nuvio do PC e digite o código exibido na tela:"` + linha com campo estruturado de 6 dígitos (`40.dp` visual em hitbox `48.dp`) e botão primário **`"Vincular Nuvio"`** (`40.dp` visual em hitbox `48.dp`).
- **Modal Flutuante de Pareamento do Computador (`PairPinDialog`):** superfície com **Vidro `REGULAR` elevado**, código de 6 dígitos grande e legível, botões com visual `38.dp` e hitbox `>= 48.dp`.
- **Pareamento por QR Code (`Escanear QR` + `QrScannerModal` — Fase 3):**
  - **Botão `Escanear QR` (`minHeight = 48.dp, minWidth = 48.dp`, visual `36.dp` em `ContentCellSurface`):** presente nos cards `CONEXÃO COM O COMPUTADOR` e `CONTROLE DIRETO DO NUVIO` da aba `Ajustes`.
  - **Modal / Tela de Câmera Limpa (`QrScannerModal`):**
    - Visor ao vivo da câmera com **moldura de leitura e cápsulas de topo/rodapé em `GlassVariant.CLEAR`** (uso legítimo de `CLEAR` sobre vídeo ao vivo da câmera, com `+35%` de escurecimento adaptativo se o ambiente for claro).
    - Exibe instrução curta (`"Aponte para o QR Code exibido no Nuvio ou Companion do PC"`), feedback instantâneo quando detectado e botões **`Digitar código`** e **`Cancelar`** (`>= 48.dp × 48.dp`).
- **Atalho Rápido na Central de Controle do Android (`Quick Settings Tile` — `NuvioTileService`, Fase 3):**
  - Bloco nativo do sistema Android (`TileService`, superfície opaca do sistema, `minHeight >= 48.dp`) denominado **`Nuvio DeskRemote`** com dois estados operacionais:
    1. **Estado Conectado / Ativo:** ícone controle ativo + título **`Nuvio DeskRemote`** + subtítulo **`Casa (Wi-Fi) • 2 ms`** (toque abre imediatamente o controle remoto / D-pad).
    2. **Estado PC Desligado / Em Suspensão:** ícone raio/controle + título **`Nuvio DeskRemote`** + subtítulo **`Acordar PC (Rede)`** (toque dispara o pacote Wake-on-LAN imediatamente sem precisar abrir `Ajustes`).

---

### 5.7 Fluxo de Primeiro Acesso (`OnboardingScreen` — Android, Sem IP Fixo)

Quando o usuário abre o aplicativo Android pela primeira vez ou sem computador salvo/pareado (`savedHost.isBlank() && !pairing.isPaired()`), o app exibe o estado inicial de **Primeiro Acesso (`OnboardingScreen`)** voltado ao consumidor final, sem qualquer IP pessoal pré-configurado:

- **Regra de Camada Liquid Glass:** apenas a pílula superior `NuvioGlassTopBar` (status `"Primeiro acesso • Wi-Fi"` à esquerda e selo `"Nuvio DeskRemote"` à direita) e o dock inferior usam `GlassVariant.REGULAR`. Todos os cartões de descoberta, botões e campos de fallback usam superfícies 100% opacas (`ContentCardSurface` e `ContentCellSurface`, **zero vidro em cards**).
- **1. Descoberta Automática na Rede Wi-Fi (`UDP Discovery`):**
  - Cartão opaco estruturado (`ContentCardSurface`) com cabeçalho `"1. DESCOBERTA AUTOMÁTICA NO WI-FI"` e indicador de varredura local.
  - **Quando um computador é encontrado via broadcast UDP:** exibe célula opaca (`ContentCellSurface`, `minHeight = 56.dp`) com ícone de rede/PC, nome do computador (`"DESKTOP-SALA"`), endereço IP na rede local (`"192.168.0.50 • Companion pronto"` — valor de exemplo) e botão primário de 1 toque **`"Conectar e Parear"`** (`minWidth >= 48.dp, minHeight = 48.dp`, visual `38.dp`).
  - **Ação de atualização:** botão **`"Buscar computadores novamente no Wi-Fi"`** (`minHeight = 48.dp`, visual `34.dp` em `ContentCellSurface`).
- **2. Ação Principal em Destaque — `"Escanear QR Code do PC"`:**
  - Cartão opaco com instrução direta (`"Aponte a câmera para o QR Code exibido na janela do Nuvio DeskRemote Companion no Windows"`) e botão de destaque em largura total **`"Escanear QR Code do PC"`** (`minHeight = 48.dp`, visual `42.dp` com preenchimento primário de alto contraste), que abre o `QrScannerDialog` existente e pareia o Companion (`8787`) e a Ponte Nuvio (`47321`) em uma única leitura.
- **3. Opção Discreta de Fallback — `"Digitar endereço IP manualmente"`:**
  - Cartão opaco discreto na base (`ContentCardSurface`) sem qualquer IP fixo preenchido:
    - Campo estruturado `HostInputBox` (`value = ""`, `placeholder = "Digitar endereço IP (ex.: 192.168.0.50)"`, altura visual `38.dp` dentro de área tátil `48.dp`) + botão **`"Usar IP"`** (`minHeight = 48.dp`).
    - Linha auxiliar com botões **`"Digitar PIN (6 dígitos)"`** (abre `PairPinDialog`) e **`"Acordar PC (WoL)"`** (`minHeight = 48.dp`).

---

### 5.8 Especificação de Responsividade Android (Múltiplas Proporções e Acessibilidade)

Para garantir ergonomia, legibilidade sem truncamento (`...`) e alvos mínimos de toque (`>= 48.dp × 48.dp`) em telas estreitas (`320.dp–360.dp`), proporções alongadas (`18:9`, `20:9`, `21:9`), janelas divididas e fontes grandes de acessibilidade (`fontScale >= 1.15f`):

#### 5.8.1 `RemoteScreen.kt`: Escala Bidimensional do Cockpit (`computeCockpitMetrics`)
- **Diagnóstico:** a implementação anterior de `computeCockpitMetrics(maxHeight: Dp, isPlayer: Boolean)` avaliava apenas `maxHeight < 470.dp`, mantendo `rockerWidth = 50.dp`, `dpadDiameter = 186.dp` e `paddingHorizontal = 16.dp` mesmo em telas estreitas (`320.dp–359.dp`), o que comprimia a linha `[Rocker Volume] + [D-Pad] + [Rocker Canal]`.
- **Assinatura e Métricas Obrigatórias:**
  ```kotlin
  private data class RemoteCockpitMetrics(
      val horizontalPadding: Dp,
      val rockerWidth: Dp,
      val rockerHeight: Dp,
      val dpadDiameter: Dp,
      val centerOkSize: Dp,
      val satelliteSize: Dp,
      val sideGap: Dp,
  )
  ```
- **Tabela de Breakpoints (`maxWidth` + `maxHeight` + `fontScale`):**

| Classe Responsiva | Condição (`BoxWithConstraints` + `LocalDensity`) | `horizontalPadding` | `rockerWidth` | `rockerHeight` | `dpadDiameter` (com clamp horizontal) | `centerOkSize` | `satelliteSize` (visual / hitbox) | `sideGap` |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`CompactNarrow`** | `maxWidth < 345.dp` **ou** `maxHeight < 430.dp` | `10.dp` | `48.dp` | `144.dp` (3 zonas `48×48.dp`) | `minOf(156.dp, maxWidth - 132.dp).coerceAtLeast(144.dp)` | `48.dp` | `36.dp` visual em `48×48.dp` | `4.dp` |
| **`CompactStandard`** | `maxWidth in 345.dp..375.dp` **ou** `maxHeight in 430.dp..490.dp` **ou** `isPlayer` **ou** `isSheetExpanded` **ou** `fontScale >= 1.20f` | `14.dp` | `48.dp` (`<360.dp`) / `50.dp` (`>=360.dp`) | `148.dp` (`Player`) / `156.dp` (`Navegar`) | `minOf(if (isPlayer) 160.dp else 166.dp, maxWidth - (horizontalPadding * 2) - (rockerWidth * 2) - 16.dp)` | `56.dp` | `38.dp` visual em `48×48.dp` | `6.dp` (`Player`) / `8.dp` |
| **`ExpandedComfort`** | `maxWidth > 375.dp` **e** `maxHeight > 490.dp` **e** `!isPlayer` **e** `!isSheetExpanded` | `16.dp` | `52.dp` | `170.dp` | `186.dp` | `68.dp` | `42.dp` visual em `48×48.dp` | `12.dp` |

- **Invariantes de Acessibilidade no Cockpit:**
  1. **Folga horizontal mínima garantida:** `dpadDiameter` nunca ultrapassa `maxWidth - (horizontalPadding * 2) - (rockerWidth * 2) - 16.dp` (`>= 8.dp` de respiro entre cada Rocker e o anel do D-Pad).
  2. **Alvos táteis `>= 48.dp × 48.dp` sem sobreposição:** mesmo no menor breakpoint (`CompactNarrow`, `rockerHeight = 144.dp` e `dpadDiameter = 156.dp`), cada um dos 3 segmentos do Rocker (`+`, `Mudo/CH`, `−`), os 4 botões direcionais (`up`, `down`, `left`, `right`) e o botão central `OK` mantêm área de toque estrita de `48.dp × 48.dp` com separação física sem interseção de hitboxes.

#### 5.8.2 `TrackpadScreen.kt`: Comportamento Responsivo dos 4 Chips de Topo (`TrackpadQuickActions`)
- **Diagnóstico:** os 4 chips (`Teclado`, `Arrastar`, `Mudo PC`, `Apagar tela`) em uma única linha de 4 colunas truncam com reticências (`"Apagar t..."`) quando `maxWidth < 360.dp` ou quando o usuário ativa fonte grande no Android (`fontScale >= 1.15f`).
- **Regras Responsivas em 3 Níveis (`BoxWithConstraints` em `TrackpadQuickActions`):**

| Modo | Condição | Layout da Grade | Ícone + Tipografia | Rótulos Exibidos (Zero Truncamento) | Ajuste Automático de Altura Vertical |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1. `Standard4Col`** | `maxWidth >= 360.dp` **e** `fontScale < 1.15f` | `1 linha × 4 colunas` (`gap = 6.dp`, visual `44.dp`, hitbox `48.dp`) | Ícone `16.dp` acima + `12.sp` (`KeyLabelCompact`) | `Teclado` · `Arrastar` (ou `Soltar`) · `Mudo PC` · `Apagar tela` | Padrão (`>= 52%` da tela para a superfície tátil). |
| **2. `Compact4Col`** | `maxWidth in 328.dp..359.dp` **e** `fontScale < 1.25f` | `1 linha × 4 colunas` (`gap = 4.dp`, visual `42.dp`, hitbox `48.dp`) | Ícone `15.dp` acima + `11.sp` (`letterSpacing = -0.01.em`) | `Teclado` · `Arrastar` (ou `Soltar`) · `Mudo` · `Tela Off` | Mantém 1 linha para preservar 100% da altura da área tátil sem cortar texto. |
| **3. `AccessibleGrid2x2`** | `maxWidth < 328.dp` **ou** `fontScale >= 1.25f` | **Quebra limpa em grade `2×2`** (`2 linhas × 2 colunas`, `gap = 6.dp`, visual `38.dp`, hitbox `48.dp`) | Ícone `16.dp` **ao lado** do texto (layout horizontal em `Row`) + `12.sp` SemiBold | `Teclado` · `Arrastar` (ou `Soltar`) · `Mudo PC` · `Apagar tela` | `TrackpadSensitivityCard` inicia recolhido (`sensitivityExpanded = false`) para garantir que `TrackpadTouchSurface` mantenha `>= 50%` da altura útil. |

---

### 5.9 Janela Desktop Nativa Windows (Tauri v2 / WebView2) — `Nuvio DeskRemote Companion` e Bandeja do Sistema

O servidor Windows deixa de abrir abas no navegador e passa a contar com uma **janela desktop nativa compacta (`Nuvio DeskRemote Companion`)** construída em **Tauri v2 / WebView2**, integrada à **Bandeja do Sistema (System Tray)**.

#### 5.9.1 Diretrizes Visuais e Estrutura da Janela (`780 × 560 px` / Compacta)
- **Liquid Glass Estrito no Desktop (`GlassVariant.REGULAR`):**
  - **Onde aplica vidro:** exclusivamente na barra superior flutuante da janela (`CompanionGlassHeader`) e no menu flutuante da bandeja do sistema (`TrayContextMenu`).
  - **Onde NUNCA aplica vidro:** cartões de pareamento QR/PIN, linhas de status das portas `8787`/`47321`, lista de dispositivos pareados, seletor de caminho do `Nuvio.exe`, switches e caixa de URL do túnel Cloudflare usam superfícies 100% opacas (`ContentCardSurface` `#13161E` no escuro / `#FFFFFF` no claro; `ContentCellSurface` `#191D28` / `#F3F6FB`), garantindo contraste WCAG AA/AAA em tema escuro e tema claro.
- **Cabeçalho Flutuante (`CompanionGlassHeader` — `GlassVariant.REGULAR`):**
  - À esquerda: ícone do app + título **`Nuvio DeskRemote Companion`** + badge `"Windows Nativo"` + LED `"Ativo na Rede Local"`.
  - À direita: controles de janela com área clicável `>= 48 × 48 px` (`Minimizar para a bandeja`, `Reiniciar servidor`, `Fechar para a bandeja`).

#### 5.9.2 Painel Principal: Pareamento Intuitivo (`QR Code + PIN de 6 dígitos`)
- Exibido automaticamente em destaque na coluna esquerda quando nenhum celular está conectado/detectado na sessão ativa (ou quando o usuário aciona `"Parear celular / QR Code"` na bandeja):
  - **QR Code de alta nitidez (`116 × 116 px` sobre base branca `#FFFFFF`)**: codifica o payload local (`nuvio://pair?host=<LAN_IP>&port=8787&pin=<PIN6>`) para pareamento instantâneo em 1 leitura pelo app Android.
  - **PIN de 6 dígitos em destaque (`482 915`)**: tipografia grande tabular (`20–24px Bold`, `letter-spacing: 0.18em`) dentro de célula opaca `ContentCellSurface`.
  - **Contador de validade de `120s`**: badge dinâmico (`"Expira em 01:48 (120s)"`) + barra de progresso regressiva de 120 segundos.
  - **Botão `"Gerar novo código (120s)"`**: hitbox `>= 48 px` (visual `36 px`), invalida o código anterior e gera novo par QR + PIN imediatamente.

#### 5.9.3 Bloco de Status Claro (Companion `8787`, Ponte Nuvio `47321` e IP Wi-Fi)
Cartão opaco (`ContentCardSurface`) com 3 linhas estruturadas (`ContentCellSurface`):
1. **Status do Companion:** LED verde + `"Servidor Companion"` + badge **`"Ativo • Porta 8787"`**.
2. **Status da Ponte Nativa com o Nuvio HTPC (`Porta 47321`):**
   - **Estado Conectado:** LED verde + `"Ponte Nuvio HTPC"` + badge **`"Conectada • Porta 47321"`**.
   - **Estado de Aviso Amigável (quando o Nuvio não está aberto ou precisa de vínculo):** LED âmbar + `"Ponte Nuvio HTPC (Porta 47321)"` + mensagem amigável **`"Nuvio HTPC não está aberto ou aguarda vínculo em Ajustes > Controle pelo celular"`** + botão de ação rápida **`"Abrir Nuvio"`** (`>= 48 px`).
3. **IP da Rede Wi-Fi Atual:** ícone Wi-Fi + **`"IP da rede Wi-Fi: 192.168.0.50"`** (exemplo genérico detectado da interface LAN) + badge `"Rede Local"`.

#### 5.9.4 Lista Opaca de Dispositivos Pareados
Cartão opaco (`ContentCardSurface`) listando os celulares autorizados:
- Cada item (`ContentCellSurface`, `minHeight = 48 px`, **zero vidro**) exibe:
  - Nome do celular (ex.: `"Smartphone Sala"`, `"Android Quarto"`).
  - Último acesso e status (ex.: `"Conectado agora • Wi-Fi local (2 ms)"`, `"Último acesso: ontem às 22:15"`).
  - Botão **`"Revogar"`** em 1 clique (`minWidth = 72 px, minHeight = 48 px`, visual `32 px`), que invalida o token do dispositivo imediatamente.

#### 5.9.5 Controles do Computador (`Nuvio.exe`, Inicialização e Acesso Remoto)
Cartão opaco (`ContentCardSurface`) na coluna direita com 3 controles principais:
1. **Seletor do Caminho do `Nuvio.exe`:**
   - Exibe status `"Detectado automaticamente"` e o caminho configurado (`"%LOCALAPPDATA%\\Programs\\Nuvio\\Nuvio.exe"`) em campo opaco + botão **`"Procurar..."`** (`minHeight = 48 px`, abre seletor nativo de arquivo `.exe` do Windows).
2. **Toggle `"Iniciar com o Windows"`:**
   - Linha interativa (`minHeight = 48 px`) com `NuvioGlassSwitch` (`"Abre silenciosamente na bandeja ao entrar no Windows"`).
3. **Toggle `"Acesso Remoto (fora de casa)"` — Desligado por Padrão:**
   - **Estado padrão (`Desligado / false`):** subtítulo `"Desligado por padrão • Conexão restrita à rede Wi-Fi local"`. Nenhum túnel externo é iniciado sem ação explícita do usuário.
   - **Estado ativo (`Ligado / true`):** inicia o túnel Cloudflare e exibe bloco opaco com:
     - **URL do túnel Cloudflare** (ex.: `https://desk-nuvio.trycloudflare.com`) + botão **`"Copiar URL"`** (`minHeight = 48 px`).
     - **Aviso obrigatório de segurança `LAN_ONLY`:** **`"Segurança: PIN de 6 dígitos e novo pareamento continuam restritos à rede local (LAN_ONLY). Apenas dispositivos já pareados no Wi-Fi podem conectar via túnel."`**

#### 5.9.6 Bandeja do Sistema (`System Tray Context Menu`)
Ao clicar no ícone do **Nuvio DeskRemote** na bandeja do Windows (ao lado do relógio), abre-se um menu de contexto compacto em `GlassVariant.REGULAR` com exatamente 4 ações principais (`minHeight = 48 px` em modo toque/acessibilidade):
1. **`Abrir Nuvio DeskRemote`** — restaura e traz a janela nativa do Companion para o primeiro plano.
2. **`Parear celular / QR Code`** — abre a janela focada diretamente no painel de QR Code + PIN de 6 dígitos (`120s`).
3. **`Reiniciar servidor`** — reinicia o servidor Companion (`8787`) e reconecta a ponte nativa do Nuvio (`47321`).
4. **`Sair`** — encerra o servidor Companion, fecha túneis ativos e remove o ícone da bandeja.

---

### 5.10 Identidade Visual e Opções de Ícone do `Nuvio DeskRemote` (Baseadas no Ícone Oficial do Nuvio)

Partindo diretamente da assinatura visual oficial do **Nuvio** — o **triângulo Play arredondado fluido com camadas/ondas internas em gradiente ciano vibrante (`#22D3EE`) -> azul royal (`#2563EB`) -> índigo (`#4F46E5`) -> violeta/magenta (`#9333EA` / `#D946EF`) e miolo interno escuro (`#101522`)** — foram desenhadas **3 propostas limpas e icônicas** que preservam a identidade imediata da marca Nuvio enquanto comunicam claramente a função de **controle remoto do Nuvio (`Nuvio DeskRemote`)**:

| Opção | Nome do Conceito | Composição Visual (`108×108dp` Android / `256×256px` Windows `.ico`) | Adaptação Bandeja Windows (`16×16` / `24×24px`) & Monocromático Android 13+ |
| :--- | :--- | :--- | :--- |
| **Opção 1 *(Recomendada)*** | **Nuvio Play + Controle Direcional no Núcleo (`D-Pad Core`)** | Mantém 100% a silhueta externa e as ondas fluidas originais do Play do Nuvio (`ciano -> azul -> violeta/magenta` com borda especular Liquid Glass), substituindo o triângulo branco simples dentro do miolo escuro (`#101522`) por um **núcleo de controle remoto**: **4 setas direcionais (chevrons cardinais) brancas nítidas + ponto central luminoso (`OK`)**. | Silhueta do Play fluido do Nuvio com a cruz de 4 pontos/setas e núcleo central em alto contraste + LED de status na bandeja do Windows 11. |
| **Opção 2** | **Nuvio Play + Ondas de Sinal Remoto (`Remote Signal & Tactile Core`)** | O Play arredondado fluido do Nuvio com seu miolo escuro (`#101522`) abrigando um **botão tátil concêntrico de controle (anel + núcleo Play branco)** e, emanando da ponta direita do Play, **2 arcos luminosos de transmissão/sinal remoto** em ciano Liquid Glass. | Play do Nuvio acompanhado do arco duplo de sinal remoto na ponta direita, leitura instantânea em `16px`. |
| **Opção 3** | **Nuvio Play + Cursor/Controle Híbrido (`HTPC + PC Cursor Core`)** | O Play arredondado fluido do Nuvio e seu miolo escuro (`#101522`), onde o elemento branco central assume a geometria de uma **seta de cursor/navegação aerodinâmica com arco tátil**, unindo a marca Nuvio ao controle remoto de HTPC e PC em uma única forma limpa, sem ícones extras ao redor. | Play do Nuvio com o recorte interno em formato de seta de cursor/navegação de alto contraste. |

#### Regras Técnicas de Exportação do Ícone Escolhido
1. **Android Adaptive Icon (`mipmap-anydpi-v26`):**
   - `ic_launcher_background.xml`: fundo escuro profundo (`#080B14 -> #0F172A`) com leve brilho atmosférico ciano/violeta (ou variante clara/transparente no `.ico` desktop).
   - `ic_launcher_foreground.xml`: o Play fluido do Nuvio com ondas em camadas (`clip-path` na silhueta arredondada) e miolo escuro `#101522` contido dentro da **Safe Zone central de `66×66dp`** (em viewport `108×108dp`), garantindo zero corte em máscaras circular, squircle ou gota.
   - `ic_launcher_monochrome.xml`: silhueta do Play do Nuvio com o miolo recortado e o símbolo interno de controle remoto em traço único (`#FFFFFF`) para *Themed Icons* do Android 13+.
2. **Windows Executável e Bandeja (`Tauri v2 icons/`):**
   - `icon.ico` multi-resolução (`256×256`, `128×128`, `64×64`, `48×48`, `32×32`, `16×16 px`) podendo usar o glifo Play recortado direto (como no `htpc_logo_glyph.png` / `nuvio_app_icon.png`) ou sobre base squircle escura.
   - `tray-icon.png` / `tray-icon.ico` (`16×16` e `24×24` @1x/@2x): glifo Nuvio Play de alto contraste com o núcleo de controle remoto simplificado para nitidez máxima na barra de tarefas do Windows 11.

---

## 6. Matriz de Verificação Rápida para Implementação

| Critério | Meta Obrigatória | Como Validar no Código / Tela |
| :--- | :--- | :--- |
| **Material 3** | `0` imports `androidx.compose.material3.*` | Busca estática em `android/` e remoção da dependência no `build.gradle.kts`. |
| **Higienização & Rebranding** | Nome público **Nuvio DeskRemote**; zero IPs pessoais fixos, nomes de usuários locais ou diretórios de perfil local | Busca estática no código, `DESIGN-SPEC.md` e mockups HTML/PNG. |
| **Separação de Modos** | Zero botões de PC em `Remote`/`Apps`; zero botão Nuvio em `Trackpad`/`Tela`; chave de contexto apenas no Modo Nuvio | Inspeção por tela (`RemoteScreen`, `DynamicContextDock`, `AppsScreen`, `TrackpadScreen`, `ScreenShareScreen`, `SettingsScreen`, `OnboardingScreen`). |
| **Tema Único & Fallbacks** | `NuvioGlassTheme` único cobrindo `REGULAR`, `CLEAR (+35% claro)` e `API < 31 / 31-32 / 33+` | Emulador/dispositivos API 29/30, API 31/32 e API 33+. |
| **Camada de Vidro** | Só na camada funcional flutuante (zero em listas/cards/trackpad/vídeo, tanto no Android quanto no Desktop Companion) | Revisão visual dos mocks e chamadas de `Modifier.nuvioGlass(...)`. |
| **Responsividade Android** | `computeCockpitMetrics(maxWidth, maxHeight)` sem espremer rockers/D-Pad em `320–360dp`; `TrackpadQuickActions` sem truncar em `320dp` ou `fontScale >= 1.25f` | Auditoria DOM Edge CDP + pré-visualização Compose em `320dp`, `360dp` e `fontScale = 1.3f`. |
| **Desktop Companion & Onboarding** | Janela Tauri v2 com QR + PIN (`120s`), status `8787`/`47321`/IP, lista `Revogar`, `Nuvio.exe`, Acesso Remoto desligado por padrão (`LAN_ONLY` no PIN) e Tray Menu; Onboarding Android sem IP fixo | Inspeção dos mockups `mockup-deskremote-*.png` e fluxo de primeiro acesso no Android. |

---

## 7. Entregáveis desta Pasta (`ui/`)

1. **`DESIGN-SPEC.md`**: esta especificação definitiva do **Nuvio DeskRemote** (Android + Windows Desktop Companion em Liquid Glass).
2. **`mockup-interactive.html`**: protótipo interativo completo em escala real (`360×800dp` retrato Android, `800×360dp` paisagem Android e `780×560px` janela Desktop Windows Companion) com:
   - Seletor de todas as telas Android (incluindo **Primeiro Acesso / Onboarding** e **Responsividade 320dp / Fonte Grande**) e da **Janela Desktop Windows (`Nuvio DeskRemote Companion` + Menu da Bandeja / Tray)**;
   - Alternador de **Tema Escuro vs Fundo Claro** + simulação de taskbar clara na aba `Tela`;
   - Simulador de **API Android (`API 33+`, `API 31–32`, `API < 31`)**;
   - Alternador de **Redução de Movimento** (`?rm=1` e `@media (prefers-reduced-motion: reduce)`).
3. **PNGs renderizados diretamente do mockup atualizado (`ui/mockups/`):**
   - `mockups/mockup-deskremote-overview.png` (Painel geral das entregas do **Nuvio DeskRemote**: Janela Desktop Windows nos 2 estados + Onboarding Android + Responsividade `RemoteScreen`/`TrackpadScreen`)
   - `mockups/mockup-deskremote-companion-pairing.png` (Janela Desktop Nativa Windows **Nuvio DeskRemote Companion** com Pareamento QR Code + PIN de 6 dígitos em destaque `120s`, Status Companion `8787` + Ponte Nuvio `47321` + IP Wi-Fi, Dispositivos Pareados com botão `Revogar` e Controles do PC com `Acesso Remoto` desligado por padrão)
   - `mockups/mockup-deskremote-companion-remote-tray.png` (Janela Desktop Nativa Windows **Nuvio DeskRemote Companion** com `Acesso Remoto (fora de casa)` ligado exibindo URL Cloudflare + aviso `LAN_ONLY`, aviso amigável da Ponte Nuvio `47321` e **Menu de Contexto da Bandeja do Sistema / Tray**)
   - `mockups/mockup-deskremote-onboarding.png` e `mockups/mockup-deskremote-onboarding-light.png` (Tela de **Primeiro Acesso / Onboarding Android** em claro e escuro, sem IP fixo: Descoberta automática Wi-Fi UDP com botão 1 toque `Conectar`, botão de destaque `Escanear QR Code do PC` e fallback discreto `Digitar endereço IP manualmente`)
   - `mockups/mockup-deskremote-responsivo.png` (Demonstração visual da **Responsividade Android `320dp` e Fonte Grande** para `RemoteScreen.kt` e `TrackpadScreen.kt`, zero truncamento e alvos `>= 48dp`).

---

## 8. Decisões Consolidadas de Design e Arquitetura UI/UX

1. **Separação Visual (`28–44.dp`) vs. Hitbox Acessível (`>= 48.dp`):** redução visual de todos os botões do app mantendo hitbox transparente mínima de `48.dp × 48.dp` (via wrappers concêntricos sem blocos pesados).
2. **Trackpad sem duplicação, área ampliada e chips responsivos:** `Alt+Tab` e `Cortina` ficam apenas no topo; barra de 4 ações (`Teclado`, `Arrastar`, `Mudo PC`, `Apagar tela`) adapta-se automaticamente entre rótulos completos (`>= 360.dp`), rótulos curtos canônicos sem truncar (`328–359.dp`) e grade `2×2` horizontal (`< 328.dp` ou `fontScale >= 1.25f`).
3. **Tela Cheia em baixa opacidade real e simetria na coluna direita:** remover captura do fundo opaco do app (`underlayLayer`) acima do `VideoStreamViewport`, reduzir opacidade de repouso (`0.22f–0.44f`) e barra ativa (`0.34f–0.62f` com drop-shadow de contraste), encolher visuais (`Sair` `34.dp`, `Orbe` `36.dp` alinhados em `right = 10.dp`, `^ Barra` `28×86.dp`, barra ativa `40.dp` slim).
4. **Simetria e Responsividade Bidimensional do Cockpit `Remote`:** `computeCockpitMetrics(maxWidth, maxHeight)` escala rockers (`48–52.dp × 144–170.dp`), D-Pad (`144–186.dp`) e satélites (`36–42.dp` visuais em hitbox `48.dp`) garantindo folga mínima de `8.dp` entre componentes mesmo em telas de `320.dp`.
5. **Rebranding Público (`Nuvio DeskRemote`), Onboarding Limpo e Companion Desktop Nativo:** remoção de IPs fixos pessoais, introdução da tela de primeiro acesso com descoberta UDP + QR Code em destaque, e janela nativa Windows (Tauri v2 / WebView2) com pareamento QR/PIN de `120s`, acesso remoto desligado por padrão (`LAN_ONLY` para PIN) e menu de bandeja do sistema.
