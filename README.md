# Volume Guard

Aplicativo Android leve para:
- ajustar o volume de mídia para uma porcentagem configurada (padrão 70%) a cada 60 segundos;
- opcionalmente bloquear a tela após um intervalo (padrão 15 segundos; função desativada por padrão);
- iniciar o serviço após reinicialização, quando permitido pelo Android e pelo fabricante.

## Gerar APK no GitHub Actions

1. Crie um repositório no GitHub.
2. Envie todo o conteúdo desta pasta para a raiz do repositório.
3. Abra **Actions** e execute **Build Android APK** (ou faça push para `main`/`master`).
4. Abra a execução concluída e baixe o artefato `VolumeGuard-debug-apk`.
5. Extraia o ZIP do artefato e instale `app-debug.apk`.

O APK debug serve para teste e instalação manual. Para publicar na Google Play, é necessário configurar assinatura de release e cumprir as políticas atuais da loja.

## Permissão para bloquear a tela

Ative a função na interface e toque em **Solicitar permissão para bloquear a tela**. Se o diálogo não abrir:
Configurações → Segurança / Segurança e privacidade → Administradores do dispositivo / Apps de administração → Volume Guard → Ativar.

O nome e o caminho variam por fabricante. Pesquise nas Configurações por “administrador do dispositivo”.

## Inicialização automática

Ative “Iniciar automaticamente após reiniciar o celular”. O app precisa ter sido aberto pelo menos uma vez após a instalação. Alguns fabricantes exigem permitir “Início automático” / “Auto start” e permitir atividade em segundo plano. O Android ou o fabricante ainda podem impedir a inicialização em alguns cenários.

## Limitações conhecidas

- O app controla o volume de mídia (`STREAM_MUSIC`), não alarmes, chamadas ou notificações.
- A porcentagem é aproximada: o Android usa passos discretos de volume, então 70% pode ser arredondado para o passo mais próximo.
- O app não ignora nem contorna limites de segurança de volume impostos pelo Android/fabricante. Se o sistema bloquear a alteração, a notificação informa que não foi possível aplicar o volume.
- A função de bloqueio da tela depende da permissão de administrador do dispositivo.
- A contagem do bloqueio começa ao receber o evento de tela ligada; algumas implementações/fabricantes podem atrasar ou restringir eventos em segundo plano.
- O Android moderno impõe restrições à execução em segundo plano e ao início automático. Desativar a otimização de bateria pode ajudar, mas não garante execução permanente.
