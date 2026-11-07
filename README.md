# Recâmera — protótipo inicial

Projeto Android nativo (Kotlin + CameraX), preparado para compilar APK de teste pelo GitHub Actions.

## O que esta versão realmente faz
- Abre a câmera traseira.
- Modos de interface: Automático, Lua, Noite, Retrato e Paisagem (nesta versão são seletores de modo; ainda não aplicam pipelines especializados).
- Controle de zoom por slider (escala logarítmica), limitado ao mínimo/máximo que a câmera informa.
- Foco por toque: toque na imagem para focar e medir exposição naquele ponto; segure para travar foco/exposição (toque de novo para liberar).
- Controle de compensação de exposição (clarear/escurecer).
- Captura sequencial de 6 fotos (8 nos modos Lua e Noite).
- Salva uma foto-base na pasta Pictures/Recamera.

## Limitação importante
Esta é uma primeira base compilável, não a câmera final prometida. A sequência ainda NÃO é alinhada nem empilhada; o app salva uma foto-base e descarta as demais. Isso evita prometer ganho de qualidade falso. As próximas etapas são: seleção automática das melhores imagens, alinhamento/registro, empilhamento real (OpenCV), teste em S24 e S24 FE, e depois modelos TFLite/ONNX compatíveis.

## API de IA
A tela de configurações/API ainda não está implementada nesta base. Não coloque uma chave Gemini diretamente no código ou no GitHub. Quando a integração for criada, a chave deverá ser guardada localmente com armazenamento seguro e o envio de fotos será opcional, com aviso explícito. Uma chave exposta no APK pode ser extraída; para uso comercial, prefira um backend próprio.

## Requisitos
- Android Studio/Gradle não são necessários no seu computador se usar o GitHub Actions.
- GitHub Actions compila o APK de depuração (não é APK assinado para publicação na Play Store).
