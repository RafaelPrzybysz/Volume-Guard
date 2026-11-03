# Volume Guard 1.4

Aplicativo Android leve para:
- ajustar o volume de mídia para uma porcentagem configurada (padrão 70%) a cada 60 segundos;
- opcionalmente bloquear a tela após um intervalo (padrão 15 segundos; função desativada por padrão);
- opcionalmente tentar recuperação pela interface do sistema quando a API normal não aplicar o volume;
- detectar tela dividida e tentar expandir as Configurações de som antes de usar coordenadas de tela cheia;
- detectar orientação paisagem e, com permissão especial, travar temporariamente em retrato durante a recuperação;
- opcionalmente tocar em uma posição calibrada no diálogo de inicialização de uma ROM;
- iniciar o serviço após reinicialização, quando permitido pelo Android e pelo fabricante.

## Gerar APK pelo GitHub Actions

1. Extraia este ZIP e envie **todo o conteúdo da pasta** para a raiz do repositório GitHub, substituindo os arquivos antigos nos mesmos caminhos e preservando a pasta oculta `.github/workflows`.
2. Faça o commit diretamente na branch `main`.
3. Abra **Actions** e execute **Build Android APK** (ou aguarde o push para `main`/`master`).
4. Abra a execução concluída e baixe o artefato `VolumeGuard-debug-apk`.
5. Extraia o ZIP do artefato e instale `app-debug.apk`.

O projeto usa `versionCode 5` e `versionName 1.4`. É um APK debug para instalação manual. Esta versão ainda precisa ser compilada pelo GitHub Actions e testada no aparelho; não foi possível compilar neste ambiente porque o Android SDK/Gradle não está instalado.

## Funcionamento normal

O serviço controla somente o volume de mídia (`STREAM_MUSIC`). A cada ciclo, tenta aplicar o nível solicitado pela API normal até cinco vezes, verificando o valor efetivo depois de cada tentativa. O modo alternativo fica desligado por padrão e não abre Configurações quando o ajuste normal funciona.

## Recuperação alternativa de volume (experimental)

A recuperação alternativa é opcional e só é tentada quando o app detecta que a API não aplicou o volume-alvo. Requer habilitar manualmente **Volume Guard — recuperação de volume** em Configurações → Acessibilidade.

1. Ative **Tentar recuperação pela tela se o volume falhar**.
2. Toque em **Calibrar posição da barra Media volume**. Na tela de som, arraste a bolinha vermelha para o ponto da barra que corresponde ao volume-alvo configurado e salve a posição.
3. Toque em **Calibrar posição do OK do aviso**. Na tela de som, toque manualmente na barra em um nível alto para abrir o aviso. Arraste a bolinha para o botão OK e salve a posição.
4. Volte ao Volume Guard e toque em **Salvar configurações**. Inicie ou reinicie o serviço.

Quando a API não aplicar o nível pedido, o app pode abrir as Configurações de som, aguardar o intervalo configurado, acordar a tela e aguardar novamente antes de tocar na posição salva da barra. Se o aviso de volume seguro for reconhecido, aguarda o mesmo intervalo e valida novamente o aviso antes de tocar na posição salva do OK. O tempo é configurável em **Tempo entre cada etapa da recuperação**, padrão 2 segundos, de 1 a 10 segundos.

A recuperação pode repetir o toque na barra até cinco vezes, verificando entre as tentativas se o nível foi aplicado ou se o aviso apareceu. O ajuste normal pela API também foi ampliado para até cinco tentativas. A cada novo ciclo do serviço, se o nível continuar abaixo do alvo, ele pode tentar novamente; há um intervalo mínimo de 45 segundos entre tentativas de recuperação visual, além do intervalo normal configurado. Se o Android encerrar o processo durante a recuperação, o serviço limpa um estado antigo preso após três minutos e volta a permitir novas tentativas.

### Tela dividida e orientação

Durante a recuperação, o app tenta detectar a janela divisora da tela dividida. Quando detecta, usa a ação global de acessibilidade para tentar tirar a janela de Configurações do modo encaixado. Só usa as coordenadas de tela cheia depois de confirmar que a tela não está mais dividida. Se a ROM não disponibilizar a ação ou a divisão continuar ativa, o toque é cancelado para evitar clicar em posição errada.

O Android só permite executar a ação global de tela dividida quando o sistema a oferece ao serviço de acessibilidade; isso varia conforme aparelho/ROM. Ao terminar, o app tenta fechar a página de som com Voltar. O Android 9 não oferece uma API pública que garanta restaurar exatamente o mesmo par de aplicativos e a mesma proporção da tela dividida; o retorno ao estado anterior é uma tentativa do sistema, não uma garantia.

A opção **Forçar orientação retrato durante a recuperação** fica ativada por padrão. Para girar temporariamente a tela, conceda a permissão especial **Modificar configurações do sistema** ao Volume Guard. O app salva a configuração de rotação anterior e tenta restaurá-la ao terminar. Se a permissão não for concedida, converte as coordenadas calibradas em retrato para a orientação paisagem atual, como alternativa. Calibre os pontos em retrato.

### Limitações da recuperação

A tela e o aviso variam conforme fabricante, idioma, resolução e versão do Android. Antes do toque na barra, o serviço procura a tela de som em todas as janelas acessíveis; antes do toque em OK, procura o aviso de segurança em todas as janelas visíveis e valida novamente depois da espera. Se não conseguir reconhecer a tela ou aviso, não toca às cegas. O Android/fabricante ainda pode impedir o aumento; não há garantia de atingir 100% nem de ultrapassar os limites de segurança auditiva.

O serviço de acessibilidade consegue ler a árvore de elementos visíveis e executar gestos enquanto está ativado. Ative-o somente se concordar com esse acesso e com a função descrita. O código limita os gestos automáticos às posições calibradas e às opções que você ativou.

## Desligar a tela

1. Ative **Ativar bloqueio automático da tela**.
2. Toque em **Solicitar permissão para bloquear a tela** e ative Volume Guard em Apps de administrador do dispositivo.
3. Configure o intervalo (padrão 15 segundos).

A permissão de administrador é usada apenas para bloquear a tela. Se o pedido automático falhar: Configurações → Segurança / Segurança e privacidade → Administradores do dispositivo / Apps de administração → Volume Guard → Ativar.

## Toque no diálogo de inicialização da ROM

Esta função é separada da recuperação de volume e fica desligada por padrão. Ela só pode ser ativada quando **Iniciar automaticamente após reiniciar o celular** também estiver ligado.

1. Ative **Iniciar automaticamente após reiniciar o celular**.
2. Ative **Tocar automaticamente no OK do diálogo da ROM ao iniciar**.
3. Toque em **Armar calibração do OK da inicialização** e reinicie o telefone.
4. Quando o diálogo da ROM aparecer, arraste a bolinha vermelha até o botão OK e salve.

Depois do próximo reinício, o app só tenta o toque se as duas opções estiverem ativadas, a acessibilidade estiver ativa e conseguir reconhecer um botão visível com o rótulo exato OK/Okay. O serviço espera o mesmo intervalo definido para as etapas da recuperação antes de tocar. A tentativa fica armada por até três minutos após o sinal de inicialização e é feita uma única vez por boot. Algumas ROMs mostram seus diálogos antes de o Android liberar serviços de acessibilidade ou não expõem o botão à acessibilidade; nesses casos, o toque automático pode não funcionar. Mantenha uma forma manual de confirmar o diálogo.

## Inicialização automática

Ative **Iniciar automaticamente após reiniciar o celular**. O app precisa ter sido aberto pelo menos uma vez após instalar. Alguns fabricantes exigem habilitar “Início automático” / “Auto start” e permitir atividade em segundo plano. O Android ou o fabricante ainda podem impedir esse comportamento.

## Limitações gerais

- A porcentagem é aproximada: o Android usa passos discretos de volume.
- O app não controla alarmes, chamadas ou notificações.
- O Android pode impor limites de volume por segurança; esta recuperação não é um método garantido de desativar proteções auditivas.
- A função de bloqueio da tela depende da permissão de administrador do dispositivo.
- O Android moderno impõe restrições à execução em segundo plano e ao início automático.
