# Recâmera — protótipo inicial

Projeto Android nativo (Kotlin + CameraX), preparado para compilar APK de teste pelo GitHub Actions.

## O que esta versão realmente faz
- Abre a câmera traseira.
- Modos de interface: Automático, Lua, Noite, Retrato e Paisagem (nesta versão são seletores de modo; ainda não aplicam pipelines especializados).
- Controle de zoom por slider (escala logarítmica), limitado ao mínimo/máximo que a câmera informa.
- Foco por toque: toque na imagem para focar e medir exposição naquele ponto; segure para travar foco/exposição (toque de novo para liberar).
- Controle de compensação de exposição (clarear/escurecer).
- Captura sequencial de 6 fotos (8 nos modos Lua e Noite).
- Alinha as fotos da sequência (OpenCV/ECC), combina com média ponderada (ignora áreas com movimento) e aplica nitidez leve. Salva o resultado em Pictures/Recamera e o original em Pictures/Recamera/Originais.

## Limitação importante
Esta é uma base de testes, não a câmera final. O empilhamento da v0.3 é um primeiro pipeline offline: o ganho depende de a cena estar estável e de o celular ficar firme; se o alinhamento falhar, o app salva o primeiro quadro sem processar e avisa. As próximas etapas são: seleção automática das melhores imagens, alinhamento/registro, empilhamento real (OpenCV), teste em S24 e S24 FE, e depois modelos TFLite/ONNX compatíveis.

## API de IA (opcional, desligada por padrão)
O botão ⚙ abre as configurações: chave da API Gemini (digitada no app, cifrada com o Android Keystore), nome do modelo, botão de teste (não envia fotos) e o interruptor da IA, com aviso de consentimento. Quando ligada, a foto combinada é enviada ao Gemini e o resultado é salvo à parte em Pictures/Recamera/IA. A IA pode alterar detalhes e o tamanho da saída (1K/2K/4K) é escolhido nas configurações; por isso nunca substitui a foto combinada nem o original. Não coloque uma chave Gemini diretamente no código ou no GitHub. Quando a integração for criada, a chave deverá ser guardada localmente com armazenamento seguro e o envio de fotos será opcional, com aviso explícito. Uma chave exposta no APK pode ser extraída; para uso comercial, prefira um backend próprio.

## Requisitos
- Android Studio/Gradle não são necessários no seu computador se usar o GitHub Actions.
- GitHub Actions compila o APK de depuração (não é APK assinado para publicação na Play Store).
