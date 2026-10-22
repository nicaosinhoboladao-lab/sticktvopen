# StickTV – Player IPTV para Android TV / Xiaomi Mi TV Stick

## Como gerar o APK
1. Crie um repositório no GitHub.
2. Envie TODOS os arquivos desta pasta, incluindo a pasta `.github`.
3. Abra a aba **Actions**. O build "Gerar APK" começa sozinho (leva uns 5 minutos).
4. Quando aparecer o ✔ verde, clique no build e baixe **StickTV-APK** em "Artifacts".
5. Descompacte. Use o `StickTV.apk`.

## Instalar no Mi TV Stick
- Ative "Fontes desconhecidas" em Configurações > Preferências do dispositivo > Segurança.
- Instale com o app **Downloader** ou **Send Files to TV**.

## Controles
| Tecla | Ação |
|---|---|
| ▲ / CH+ | Próximo canal |
| ▼ / CH- | Canal anterior |
| OK ou ◀ | Abre a lista de canais |
| ▶ | Mostra informações do canal |
| Números | Vai direto ao canal |
| Segurar OK na lista | Favoritar |
| MENU / botão Configurações | Trocar URL da lista |
| VOLTAR 2x | Sair |

## Trocar a lista padrão
Edite `DEFAULT_PLAYLIST_URL` em `app/src/main/java/com/sticktv/player/Config.kt`.
