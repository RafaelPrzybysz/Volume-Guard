# Volume Guard 1.2

Aplicativo Android leve para:
- ajustar o volume de mídia para uma porcentagem configurada (padrão 70%) a cada 60 segundos;
- opcionalmente bloquear a tela após um intervalo (padrão 15 segundos; função desativada por padrão);
- opcionalmente tentar uma recuperação pela interface do sistema quando o volume não for aplicado pela API;
- iniciar o serviço após reinicialização, quando permitido pelo Android e pelo fabricante.

## Gerar APK pelo GitHub Actions

1. Envie todo o conteúdo desta pasta para a raiz do repositório GitHub, preservando a pasta oculta `.github/workflows`.
2. Abra **Actions** e execute **Build Android APK** (ou faça push para `main`/`master`).
3. Abra a execução concluída e baixe o artefato `VolumeGuard-debug-apk`.
4. Extraia o ZIP do artefato e instale `app-debug.apk`.

O projeto usa `versionCode 3` e `versionName 1.2`. A compilação de teste é um APK debug, para instalação manual.

## Funcionamento normal

O serviço tenta ajustar o volume de mídia (`STREAM_MUSIC`) pela API normal a cada intervalo configurado. O modo alternativo fica desligado por padrão e não abre configurações quando o ajuste normal funciona.

## Recuperação alternativa de volume (experimental)

A recuperação alternativa é opcional e só é tentada quando o app detecta que a API não aplicou o volume-alvo. Requer habilitar manualmente o serviço **Volume Guard — recuperação de volume** em Configurações → Acessibilidade.

Depois:

1. Ative **Tentar recuperação pela tela se o volume falhar**.
2. Toque em **Calibrar posição da barra Media volume**. Na tela de som, arraste a bolinha vermelha para o ponto da barra que corresponde ao volume-alvo configurado e salve a posição.
3. Toque em **Calibrar posição do OK do aviso**. Na tela de som, toque manualmente na barra em um nível alto para abrir o aviso de volume seguro. Arraste a bolinha para o botão OK e salve a posição.
4. Volte ao Volume Guard e toque em **Salvar configurações**. Inicie ou reinicie o serviço.

Quando a API não aplicar o nível pedido, o app pode abrir **Configurações de som**, esperar o intervalo configurado e então tentar acordar a tela. Depois espera o mesmo intervalo antes do toque na barra salva; se o serviço reconhecer o diálogo de aviso de volume seguro, espera novamente esse intervalo antes de tocar na posição salva do OK. O tempo entre etapas é configurável em **Tempo entre cada etapa da recuperação**, padrão de 2 segundos e intervalo permitido de 1 a 10 segundos. Para exemplos: com 3 segundos, cada etapa espera 3 segundos; com 4 segundos, cada etapa espera 4 segundos. Para evitar que as configurações abram continuamente, novas tentativas automáticas têm intervalo mínimo de 10 minutos.

**Limitações:** esta função é experimental. A tela e o diálogo variam conforme fabricante, idioma, tamanho da tela e versão do Android. As posições precisam ser calibradas no próprio aparelho, na orientação em que serão usadas; recalibre após mudar resolução, rotação, fonte ou versão do sistema. Antes de tocar na barra, o serviço procura indícios textuais da tela de som; se não conseguir confirmar que a tela correta está aberta, encerra a tentativa sem tocar para evitar um toque em local errado. A espera antes do toque no OK começa quando o serviço reconhece o aviso e o valida novamente antes de tocar. O app só toca na posição do OK quando reconhece texto compatível com um aviso de volume; se o aviso não puder ser reconhecido pela acessibilidade, ele não deve tocar às cegas. O Android/fabricante ainda pode impedir o aumento e não há garantia de atingir 100%.

O serviço de acessibilidade tem acesso à árvore de elementos e pode executar gestos enquanto está ativado. Ative-o somente se concordar com essa função e com os detalhes acima. Ele foi limitado no código a esta recuperação configurada e à calibração dos dois pontos.

## Desligar a tela

1. Ative **Ativar bloqueio automático da tela**.
2. Toque em **Solicitar permissão para bloquear a tela** e ative Volume Guard em Apps de administrador do dispositivo.
3. Configure o intervalo (padrão de 15 segundos).

A permissão de administrador é usada apenas para bloquear a tela. Se o pedido automático falhar: Configurações → Segurança / Segurança e privacidade → Administradores do dispositivo / Apps de administração → Volume Guard → Ativar.

## Inicialização automática

Ative **Iniciar automaticamente após reiniciar o celular**. O app precisa ter sido aberto pelo menos uma vez após instalar. Alguns fabricantes exigem habilitar “Início automático” / “Auto start” e permitir atividade em segundo plano. O Android ou o fabricante ainda podem impedir esse comportamento.

## Limitações gerais

- O app controla o volume de mídia, não alarmes, chamadas ou notificações.
- A porcentagem é aproximada: o Android usa passos discretos de volume.
- O Android pode impor limites de volume por segurança; esta recuperação não garante nem deve ser considerada uma forma garantida de desativar proteções auditivas.
- A função de bloqueio da tela depende da permissão de administrador do dispositivo.
- O Android moderno impõe restrições à execução em segundo plano e ao início automático.
