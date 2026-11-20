# Decisões técnicas

Aqui estão as principais escolhas que fiz no projeto, o motivo de cada uma e o que eu faria diferente com mais tempo.

## Ambiente

**Imagem `gvenzl/oracle-free` na versão slim-faststart, com tag fixa.** É a que o PDF recomenda e a mesma que o Testcontainers usa. Ela sobe em segundos porque o banco já vem criado. Fixei a tag pra ninguém rodar uma versão diferente da que eu testei.

**Usuários criados por script, todo o resto pelo Flyway.** Criar usuário precisa de SYSDBA, e eu não queria o Flyway rodando com esse acesso. Então o script do `db/init` roda uma vez só pra criar os três usuários, e todo o resto (tabelas, package, triggers e grants) fica versionado em migration.

**Duas execuções do Flyway, cada uma conectada como o dono dos objetos.** Primeiro roda a do `WALLET_AUDIT`, depois a do `WALLET_OWNER`, porque o trigger de auditoria depende do grant que o audit dá. A outra opção era um Flyway só, com um usuário DBA, mas aí os objetos seriam criados por um usuário com acesso demais. Desse jeito, quem dá o grant é sempre o dono do objeto.

**Senhas só no `.env`.** O `.env` não vai pro repositório e o `.env.example` fica vazio de propósito. Se faltar alguma senha, o compose já falha na hora em vez de subir com senha padrão.

## Schemas e privilégios

**A aplicação não acessa nenhuma tabela.** O `WALLET_APP` só tem `EXECUTE` no `PKG_WALLET` e `READ` em duas views. Toda escrita passa pelo package e toda leitura pelas views. Mesmo que alguém consiga a senha do app, o máximo que dá pra fazer é chamar o package, que valida tudo.

**`GRANT READ` nas views, e não `GRANT SELECT`.** Com `SELECT` a aplicação conseguiria dar `SELECT ... FOR UPDATE` ou `LOCK TABLE` e travar carteira sem passar pelo package. O `READ` só deixa consultar. As views também são `WITH READ ONLY`.

**O owner só consegue inserir na auditoria.** Ele não lê, não altera e não apaga. A ideia é que quem gera o evento não consiga mexer no registro dele.

## Modelagem

**Dinheiro em `NUMBER(15,2)` no banco e `BigDecimal` no Java.** Nada de ponto flutuante. Também deixei um `CHECK (balance >= 0)` na tabela, pra garantir que nem um `UPDATE` direto no banco deixe o saldo negativo.

**Sequences explícitas com `DEFAULT seq.NEXTVAL`.** O PDF pede sequences explícitas e assim eu controlo o cache. A do extrato tem cache maior (100) porque é onde mais tem insert. O cache pode deixar buracos na numeração quando o banco reinicia, mas isso não é problema aqui, porque o id não é usado como número de documento.

**`balance_after` em cada lançamento.** Com isso, um retry devolve exatamente a resposta original, inclusive o saldo daquele momento. Também fica fácil conferir o extrato linha a linha.

**Chave de idempotência única por carteira**, com `UNIQUE (wallet_id, idempotency_key)`. Dois clientes podem usar a mesma chave em carteiras diferentes sem dar problema, e a constraint ainda serve de segunda proteção caso a checagem do package falhe.

**V6 pra corrigir o tamanho dos textos.** Eu tinha criado `holder_name` e `description` com tamanho em bytes, e nome com acento ocupava mais espaço do que devia. Como a migration já estava commitada, criei uma nova (V6) mudando pra `CHAR` em vez de editar a antiga. Migration que já rodou não se edita.

## PKG_WALLET

**`AUTHID DEFINER`.** O package roda com os privilégios do owner, e é isso que permite a aplicação gravar sem ter grant nas tabelas.

**O commit fica dentro da procedure.** Cada chamada faz tudo e já commita: valida, trava, grava o lançamento e atualiza o saldo. Se der erro, faz rollback. Assim o Java não precisa controlar transação, e um erro na aplicação não tem como deixar lock preso no banco. O ponto negativo é que não dá pra juntar duas operações numa transação só. Se um dia precisar de transferência entre carteiras, ela vira uma procedure nova no package.

**Lock pessimista com `SELECT ... FOR UPDATE WAIT 5`.** Pensei em lock otimista com coluna de versão, mas numa carteira com muita concorrência isso vira um monte de retry, e a aplicação teria que tratar isso. Com o lock pessimista o próprio banco coloca as operações da mesma carteira em fila, e carteiras diferentes não travam uma a outra.

Usei `WAIT 5` porque esperar sem limite prenderia conexão do pool, e `NOWAIT` falharia em qualquer disputa rápida. Se passar dos 5 segundos, a API responde `409 WALLET_BUSY` com `Retry-After`. Um detalhe que só descobri testando: no Oracle 23 esse timeout dá `ORA-00054`, e não `ORA-30006` como nas versões antigas, então o package trata os dois.

**A idempotência é checada depois do lock.** Se fosse antes, duas requisições com a mesma chave chegando juntas passariam pela checagem ao mesmo tempo. Com o lock antes, a segunda espera a primeira terminar e já encontra o lançamento pronto pra devolver.

**Operação recusada não grava nada.** Se o débito foi recusado por falta de saldo, a chave continua livre, e o cliente pode depositar e tentar de novo com a mesma chave. Preferi não guardar nada de uma operação que não aconteceu.

**Códigos de erro separados por faixa:**
- 2000x: validação;
- 20100: não encontrado;
- 2020x: conflito;
- 2030x: regra de saldo;
- 2090x: tentativa de mexer no extrato ou na auditoria.

Assim fica fácil mapear pra HTTP e sobra espaço pra novos códigos. A tabela completa está no README.

**Validação no package e na API.** A API valida pra devolver uma mensagem boa sem precisar ir no banco. O package valida porque é ele que garante a regra, mesmo pra quem chamar direto pelo SQL.

## Triggers

**Imutabilidade do extrato e da auditoria.** Coloquei triggers que bloqueiam `UPDATE` e `DELETE`, e mais um trigger de DDL pra bloquear `TRUNCATE` e `DROP`, porque `TRUNCATE` não dispara trigger normal.

O ponto fraco é que o owner ou um DBA ainda conseguem desabilitar o trigger. Com mais tempo eu usaria as immutable tables ou blockchain tables do Oracle 23ai, que são feitas pra isso.

**O trigger de auditoria diferencia mudança pelo package de mudança direta.** Ele roda em qualquer mudança de saldo. Pra saber de onde veio, confere se o `last_transaction_id` mudou e se esse lançamento existe com o mesmo saldo. Se bater, marca `PACKAGE`; se não, marca `DIRECT`. Só olhar se o id mudou não era suficiente, porque alguém poderia colocar o id de um lançamento antigo (tem teste pra esse caso). Também gravo o `SESSION_USER`, que é quem estava conectado de verdade.

## API

**JDBC em vez de JPA.** Toda escrita é chamada de procedure e toda leitura é consulta em view. O JPA não ia ajudar em nada e só ia esconder o SQL.

**`ALTER SESSION SET CURRENT_SCHEMA` na conexão.** Assim não preciso colocar o nome do schema em todo SQL nem criar sinônimos. Isso não dá privilégio nenhum a mais pro app.

**Tradução dos erros do Oracle.** O tradutor procura o código Oracle dentro da exceção, transforma no erro certo da API e limpa a mensagem (tira o `ORA-` e a pilha do PL/SQL). Código que não conheço vira 500 com mensagem genérica, sem vazar nada do banco.

**Erros no padrão Problem Details com um campo `code`.** O `code` (`INSUFFICIENT_FUNDS`, `WALLET_BUSY`...) é o que o cliente usa pra tratar o erro. Os erros do próprio Spring (rota que não existe, JSON inválido etc.) saem no mesmo formato, pra ter um padrão só.

**`Idempotency-Key` no header.** É como as APIs de pagamento costumam fazer, e deixa o corpo só com os dados da operação. No retry volta o mesmo `201`, com o header `Idempotent-Replayed: true` pra indicar que foi repetição.

**Extrato com paginação por offset, no máximo 100 por página.** O período é fechado no início e aberto no fim, pra dois períodos seguidos não repetirem lançamento. O `WHERE` é montado só com os filtros que vieram, o que ajuda o banco a usar o índice.

**API key num filtro simples, sem Spring Security.** Pra uma chave só, o Spring Security seria configuração demais. A comparação da chave é em tempo constante, e o 401 sai no mesmo formato dos outros erros.

## Testes

**Unitários sem Docker e integração com Oracle de verdade.** O `mvn test` roda rápido e cobre o que tem lógica no Java (tradução de erros, contrato HTTP e validações). O `mvn verify` sobe um Oracle com Testcontainers. Não fiz teste de regra de saldo com mock, porque quem faz essa regra é o PL/SQL e o mock só ia testar ele mesmo.

**O banco dos testes usa o mesmo script e as mesmas migrations do compose.** Assim o teste valida o que vai pra entrega, e não um banco montado só pra teste.

**Cada teste cria a própria carteira.** Não dá pra limpar o banco entre um teste e outro, porque o extrato não pode ser apagado, e isso é justamente uma das coisas testadas.

**Regras do banco testadas direto pelo JDBC.** O PDF pede que o extrato não possa ser alterado nem por SQL direto, então o `DatabaseRulesIT` tenta fazer isso conectado com cada usuário.

**Concorrência testada pela API.** Disparo várias requisições ao mesmo tempo e confiro no final se o saldo, o extrato e a auditoria batem.

**Uma coisa que aprendi com os testes:** quando o trigger de DDL bloqueia um `TRUNCATE`, o erro principal que volta é `ORA-04088`, e o `ORA-20900` vem dentro da mensagem. O bloqueio funciona, mas um monitoramento que olhasse só o erro principal não ia perceber a tentativa.

## O que eu faria com mais tempo

- **Criação de carteira idempotente.** Hoje, um retry do `POST /wallets` cria outra carteira. Daria pra aceitar `Idempotency-Key` ali também.
- **Paginação por cursor** em vez de offset, que funciona melhor com extratos grandes. A contagem e a página também são duas consultas separadas, então o total pode ficar um pouco desatualizado se entrar lançamento no meio.
- **Uma API key por cliente**, com rotação, em vez de uma chave só.
- **Proteção mais forte do extrato**, com immutable tables do Oracle 23ai, já que trigger pode ser desabilitado.
- **Bloquear a conta do owner depois das migrations** (`ACCOUNT LOCK`) e só liberar quando for rodar migration.
