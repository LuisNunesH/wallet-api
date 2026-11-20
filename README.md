# Wallet API

API de carteira digital: cria carteira, faz crédito e débito e consulta saldo e extrato.

As regras principais ficam no Oracle e não no Java: saldo nunca negativo, operação nunca aplicada duas vezes, extrato imutável e auditoria de saldo. Isso é feito com PL/SQL, triggers, sequences e grants. A aplicação Spring Boot só expõe a API em cima disso, e o usuário que ela usa no banco não consegue escrever direto em nenhuma tabela.

As decisões que tomei e o que eu faria diferente estão no [DECISIONS.md](DECISIONS.md).

Stack: Java 17, Spring Boot 4.1, Oracle Database Free 23 (`gvenzl/oracle-free`), Flyway, JDBC, springdoc (Swagger), JUnit e Testcontainers.

## Como rodar

### Pré-requisitos

- Docker com Docker Compose. No Windows, Docker Desktop com WSL2. O Docker precisa de pelo menos 4 GB de memória, senão o Oracle não sobe.
- JDK 17 ou mais novo (confere com `java -version`).
- Git.

Não precisa instalar Maven, o projeto já tem o Maven Wrapper (`mvnw`).

As portas 1521 (Oracle) e 8080 (API) precisam estar livres.

### 1. Clonar

```bash
git clone https://github.com/LuisNunesH/wallet-api.git
cd wallet-api
```

### 2. Criar o `.env`

As senhas não ficam no repositório, então é preciso criar o `.env` a partir do modelo:

```bash
cp .env.example .env
```

No PowerShell: `Copy-Item .env.example .env`.

Depois é só preencher. Pra rodar local pode ser algo assim:

```properties
ORACLE_PASSWORD=Sys_Local_2026
WALLET_OWNER_PASSWORD=Owner_Local_2026
WALLET_AUDIT_PASSWORD=Audit_Local_2026
WALLET_APP_PASSWORD=App_Local_2026
API_KEY=minha-chave-local
```

- `ORACLE_PASSWORD` é a senha do SYS/SYSTEM do container.
- As três seguintes são as senhas dos schemas.
- `API_KEY` é a chave que vai no header `X-API-Key`.
- Nas senhas, usa só letras, números e `_`. Aspas, `@` e `/` quebram a criação dos usuários.

Esse mesmo arquivo é lido pelo compose e pela aplicação, então não precisa exportar nenhuma variável.

### 3. Subir o banco

```bash
docker compose up -d
```

Na primeira vez demora alguns minutos por causa do download das imagens. Depois disso sobe em uns 30 segundos.

O compose faz isso, nessa ordem:

1. `oracle` sobe o banco e, na primeira vez, roda o [db/init/01-create-schemas.sh](db/init/01-create-schemas.sh), que cria os usuários `WALLET_OWNER`, `WALLET_AUDIT` e `WALLET_APP`.
2. `migrate-audit` espera o banco ficar healthy e roda as migrations do schema de auditoria.
3. `migrate-owner` espera a anterior terminar e roda as migrations do schema principal.

Pra saber se já terminou, roda `docker compose ps -a`. O `oracle` tem que estar `healthy` e as duas migrations com `Exited (0)`:

```
SERVICE         STATUS
migrate-audit   Exited (0)
migrate-owner   Exited (0)
oracle          Up (healthy)
```

Se quiser conferir, o `docker compose logs migrate-owner` tem que terminar com `Successfully applied 10 migrations to schema "WALLET_OWNER"`.

### 4. Subir a API

Na raiz do projeto:

```bash
./mvnw spring-boot:run
```

No Windows: `.\mvnw.cmd spring-boot:run`.

Quando aparecer `Started WalletApiApplication`, a API está rodando em `http://localhost:8080`, conectada no banco como `WALLET_APP`.

Pra testar se subiu:

```bash
curl http://localhost:8080/actuator/health
```

Tem que voltar `{"groups":["liveness","readiness"],"status":"UP"}`.

### 5. Testar a API

O jeito mais fácil é pelo Swagger: abre `http://localhost:8080/swagger-ui.html`, clica em Authorize, coloca o valor do `API_KEY` do `.env` e testa por ali. O OpenAPI em JSON fica em `http://localhost:8080/v3/api-docs`.

Se preferir pelo terminal, seguem os exemplos com curl (Linux, macOS, Git Bash ou WSL).

Criar carteira:

```bash
curl -i -X POST http://localhost:8080/wallets \
  -H "X-API-Key: minha-chave-local" \
  -H "Content-Type: application/json" \
  -d '{"holderName":"Luis Nunes","holderDocument":"12345678901"}'
```

```
HTTP/1.1 201
Location: /wallets/1

{"id":1,"holderName":"Luis Nunes","holderDocument":"12345678901","balance":0.00,"createdAt":"2026-11-20T01:29:19.708042Z","updatedAt":"2026-11-20T01:29:19.708042Z"}
```

Crédito. O header `Idempotency-Key` é obrigatório, é ele que identifica a operação:

```bash
curl -i -X POST http://localhost:8080/wallets/1/transactions \
  -H "X-API-Key: minha-chave-local" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: deposito-001" \
  -d '{"type":"CREDIT","amount":100.00,"description":"Deposito inicial"}'
```

```
HTTP/1.1 201
Location: /wallets/1/transactions/1
Idempotent-Replayed: false

{"id":1,"walletId":1,"idempotencyKey":"deposito-001","type":"CREDIT","amount":100.00,"balanceAfter":100.00,"description":"Deposito inicial","createdAt":"2026-11-20T01:29:20.141643Z"}
```

Rodando o mesmo comando de novo (como se fosse um retry depois de timeout), volta a mesma resposta, com o mesmo `id`, só que com `Idempotent-Replayed: true`. O saldo continua 100.00.

Mesma chave com outro valor dá conflito:

```bash
curl -X POST http://localhost:8080/wallets/1/transactions \
  -H "X-API-Key: minha-chave-local" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: deposito-001" \
  -d '{"type":"CREDIT","amount":50.00}'
```

```json
{"detail":"Chave de idempotência deposito-001 já usada com outro payload nesta carteira","instance":"/wallets/1/transactions","status":409,"title":"Conflito de chave de idempotência","code":"IDEMPOTENCY_KEY_CONFLICT","timestamp":"2026-11-20T01:29:20.2889941Z"}
```

Débito maior que o saldo:

```bash
curl -X POST http://localhost:8080/wallets/1/transactions \
  -H "X-API-Key: minha-chave-local" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: saque-002" \
  -d '{"type":"DEBIT","amount":500.00}'
```

```json
{"detail":"Saldo insuficiente para o débito","instance":"/wallets/1/transactions","status":422,"title":"Saldo insuficiente","code":"INSUFFICIENT_FUNDS","timestamp":"2026-11-20T01:29:20.4433947Z"}
```

Saldo e extrato:

```bash
curl http://localhost:8080/wallets/1 -H "X-API-Key: minha-chave-local"
curl "http://localhost:8080/wallets/1/transactions?page=0&size=10" -H "X-API-Key: minha-chave-local"
```

```json
{"content":[{"id":2,"walletId":1,"idempotencyKey":"saque-001","type":"DEBIT","amount":30.00,"balanceAfter":70.00,"description":null,"createdAt":"2026-11-20T01:29:20.410242Z"},{"id":1,"walletId":1,"idempotencyKey":"deposito-001","type":"CREDIT","amount":100.00,"balanceAfter":100.00,"description":"Deposito inicial","createdAt":"2026-11-20T01:29:20.141643Z"}],"page":0,"size":10,"totalElements":2,"totalPages":1}
```

No PowerShell do Windows o `curl` é outro comando e o JSON não chega certo. Nesse caso dá pra usar o Swagger, o Git Bash ou o `Invoke-RestMethod`:

```powershell
$h = @{ "X-API-Key" = "minha-chave-local" }
$w = Invoke-RestMethod -Method Post -Uri http://localhost:8080/wallets -Headers $h -ContentType "application/json" -Body '{"holderName":"Joao Souza"}'

$h2 = @{ "X-API-Key" = "minha-chave-local"; "Idempotency-Key" = "deposito-ps-001" }
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/wallets/$($w.id)/transactions" -Headers $h2 -ContentType "application/json" -Body '{"type":"CREDIT","amount":25.50}'

Invoke-RestMethod -Uri "http://localhost:8080/wallets/$($w.id)" -Headers $h
```

### 6. Desligar

```bash
docker compose down
```

O banco não tem volume, então o `down` apaga os dados e o próximo `up` recria tudo do zero. Se quiser só pausar sem perder nada, usa `docker compose stop` e depois `docker compose start`.

## Testes

```bash
./mvnw test     # 55 testes unitários, não precisa de Docker (uns 10 segundos)
./mvnw verify   # unitários + 51 de integração com Oracle real, precisa do Docker (1 a 2 minutos)
```

No Windows, troca `./mvnw` por `.\mvnw.cmd`.

Os testes de integração não usam o banco do compose. O Testcontainers sobe outro Oracle, com porta e senhas aleatórias, e apaga no final, então tanto faz se o compose está ligado. Esse banco é montado com o mesmo script de init e as mesmas migrations do compose, então o teste valida exatamente o que vai pra entrega.

O que cada classe testa:

- `OracleErrorTranslatorTest`: cada `ORA-20xxx` vira o código e o status HTTP certos, e erro desconhecido não vaza detalhe do banco.
- `WalletControllerTest`: contrato HTTP, validações, headers de idempotência e 401 sem API key.
- `WalletServiceTest`: saldo insuficiente e período inválido no extrato.
- `ApiKeyFilterTest`: quais rotas são públicas e quais pedem a chave.
- `WalletApiIT`: fluxo completo por HTTP, retry, conflito, saldo insuficiente, paginação e filtro por período.
- `ConcurrencyIT`: 40 débitos ao mesmo tempo numa carteira com saldo pra só 20 (passam exatamente 20 e o saldo termina em 0.00); 30 retries iguais ao mesmo tempo (aplica uma vez só); 120 operações misturadas e duplicadas, conferindo saldo, extrato e auditoria no final.
- `DatabaseRulesIT`: conecta direto no banco com cada usuário e testa os privilégios, a imutabilidade do extrato e da auditoria (inclusive `TRUNCATE` e `DROP` pelo dono), a auditoria, as validações do package e o timeout de lock.

## API

Todas as rotas pedem o header `X-API-Key`, menos `/actuator/health`, `/swagger-ui.html` e `/v3/api-docs`.

| Método | Rota | O que faz | Retorno |
|---|---|---|---|
| `POST` | `/wallets` | cria carteira com saldo 0.00 | `201` + `Location` |
| `GET` | `/wallets/{id}` | carteira e saldo | `200` |
| `GET` | `/wallets/{id}/transactions` | extrato paginado, do mais recente pro mais antigo | `200` |
| `GET` | `/wallets/{id}/transactions/{transactionId}` | um lançamento | `200` |
| `POST` | `/wallets/{id}/transactions` | crédito ou débito | `201` + `Location` |

Criar carteira: `{"holderName": "...", "holderDocument": "..."}`. O nome é obrigatório (até 150 caracteres). O documento é opcional, mas se vier tem que ser CPF (11 dígitos) ou CNPJ (14 dígitos), só números.

Registrar operação: `{"type": "CREDIT" ou "DEBIT", "amount": 10.50, "description": "..."}`. O valor tem que ser maior que zero, com no máximo 2 casas. A descrição é opcional (até 140 caracteres).

O header `Idempotency-Key` é obrigatório no POST de transação: até 64 caracteres, com letras, números e `_ . : -` (um UUID funciona). A chave vale por carteira. Como funciona:

- chave nova: aplica a operação e volta `201` com `Idempotent-Replayed: false`;
- mesma chave e mesmo payload: não aplica de novo, devolve a resposta original com `Idempotent-Replayed: true`;
- mesma chave com payload diferente: `409 IDEMPOTENCY_KEY_CONFLICT`;
- se a operação foi recusada (saldo insuficiente, por exemplo), nada é gravado e a chave pode ser usada de novo.

Extrato: aceita `from` e `to` (ISO-8601 com fuso, ex.: `2026-11-01T00:00:00Z`), `page` (começa em 0) e `size` (de 1 a 100, padrão 20). O `from` entra no filtro e o `to` não, assim dois períodos seguidos não repetem lançamento. A resposta tem `content`, `page`, `size`, `totalElements` e `totalPages`.

### Erros

Todo erro segue o padrão Problem Details (RFC 9457), com um campo `code` a mais. É pelo `code` que o cliente deve tratar o erro:

```json
{
  "title": "Saldo insuficiente",
  "status": 422,
  "detail": "Saldo insuficiente para o débito",
  "instance": "/wallets/1/transactions",
  "code": "INSUFFICIENT_FUNDS",
  "timestamp": "2026-11-20T01:29:20.4433947Z"
}
```

Erro de validação vem também com `errors: [{"field": "...", "message": "..."}]`.

| HTTP | `code` | Quando acontece | Vem do Oracle? |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | campo, parâmetro ou header inválido | não, validação da API |
| 400 | `MALFORMED_REQUEST` | JSON inválido ou `type` que não existe | não |
| 400 | `INVALID_HOLDER_NAME` | nome vazio ou grande demais | `ORA-20001` |
| 400 | `INVALID_HOLDER_DOCUMENT` | documento fora do formato | `ORA-20002` |
| 400 | `INVALID_OPERATION_TYPE` | tipo diferente de CREDIT/DEBIT | `ORA-20003` |
| 400 | `INVALID_AMOUNT` | valor ≤ 0, mais de 2 casas ou acima do máximo | `ORA-20004` |
| 400 | `INVALID_IDEMPOTENCY_KEY` | chave fora do formato | `ORA-20005` |
| 400 | `INVALID_DESCRIPTION` | descrição com mais de 140 caracteres | `ORA-20006` |
| 401 | `UNAUTHORIZED` | `X-API-Key` faltando ou errada | não |
| 404 | `WALLET_NOT_FOUND` | carteira não existe | `ORA-20100` |
| 404 | `TRANSACTION_NOT_FOUND` | lançamento não existe nessa carteira | não |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | chave já usada com outro payload | `ORA-20200` |
| 409 | `WALLET_BUSY` | carteira travada por mais de 5s (vem com `Retry-After: 1`) | `ORA-20201` |
| 422 | `INSUFFICIENT_FUNDS` | débito maior que o saldo | `ORA-20300` |
| 422 | `BALANCE_LIMIT_EXCEEDED` | crédito passaria do saldo máximo (9.999.999.999.999,99) | `ORA-20301` |
| 500 | `INTERNAL_ERROR` | qualquer erro inesperado (a mensagem é genérica, o detalhe só vai pro log) | |

Também tem `RESOURCE_NOT_FOUND` (404), `METHOD_NOT_ALLOWED` (405), `NOT_ACCEPTABLE` (406) e `UNSUPPORTED_MEDIA_TYPE` (415) pra erro de rota e de formato.

Como escolhi os status:

- 400: a requisição está errada, e mandar igual de novo vai dar o mesmo erro.
- 409: a requisição está certa, mas bate de frente com o estado atual (chave já usada, ou carteira ocupada naquele momento, aí vale tentar de novo).
- 422: a requisição está certa, mas a regra de negócio não deixa, como no saldo insuficiente.

## Como funciona por dentro

### Schemas e privilégios

| Usuário | Pra que serve | O que pode |
|---|---|---|
| `WALLET_OWNER` | dono das tabelas, sequences, views, package e triggers. Só as migrations usam. | criar objetos no próprio schema e fazer `INSERT` na tabela de auditoria (sem poder ler, alterar ou apagar) |
| `WALLET_AUDIT` | dono da auditoria | criar objetos no próprio schema |
| `WALLET_APP` | usuário da aplicação | `CREATE SESSION`, `EXECUTE` no `PKG_WALLET` e `READ` nas duas views. Nenhum acesso a tabela nem à auditoria. |

Os usuários são criados pelo script de init (roda como SYSDBA uma vez só). O resto, inclusive os grants, é tudo migration versionada. Cada schema é migrado conectado como o próprio dono.

Nas views usei `GRANT READ` e não `GRANT SELECT`, porque com `SELECT` a aplicação conseguiria fazer `SELECT ... FOR UPDATE` e travar carteira sem passar pelo package.

### Modelagem

São três tabelas:

`WALLET_OWNER.WALLET`: a carteira e o saldo atual.

| Coluna | Tipo | Regra |
|---|---|---|
| `id` | `NUMBER(19)` | PK, vem da `SEQ_WALLET` |
| `holder_name` | `VARCHAR2(150 CHAR)` | obrigatório, não pode ser só espaço |
| `holder_document` | `VARCHAR2(14)` | opcional, 11 ou 14 dígitos |
| `balance` | `NUMBER(15,2)` | começa em 0, `CHECK (balance >= 0)` |
| `last_transaction_id` | `NUMBER(19)` | FK pro último lançamento, usado pela auditoria |
| `created_at`, `updated_at` | `TIMESTAMP WITH TIME ZONE` | |

`WALLET_OWNER.WALLET_TRANSACTION`: o extrato, que não pode ser alterado.

| Coluna | Tipo | Regra |
|---|---|---|
| `id` | `NUMBER(19)` | PK, vem da `SEQ_WALLET_TRANSACTION` |
| `wallet_id` | `NUMBER(19)` | FK pra `WALLET` |
| `idempotency_key` | `VARCHAR2(64)` | `UNIQUE (wallet_id, idempotency_key)` |
| `operation_type` | `VARCHAR2(6)` | `CREDIT` ou `DEBIT` |
| `amount` | `NUMBER(15,2)` | maior que 0 |
| `balance_after` | `NUMBER(15,2)` | saldo logo depois do lançamento |
| `description` | `VARCHAR2(140 CHAR)` | opcional |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | |

Tem um índice em `(wallet_id, created_at, id)` pro extrato.

`WALLET_AUDIT.BALANCE_AUDIT`: a auditoria, que só o banco alimenta. Guarda a carteira, o lançamento que causou a mudança, o saldo anterior e o novo, a origem (`PACKAGE` quando veio do package, `DIRECT` quando alguém mexeu no saldo por fora), o usuário do banco que estava conectado e o horário.

A aplicação lê por duas views `WITH READ ONLY`, a `V_WALLET` e a `V_WALLET_TRANSACTION`, que não mostram o `last_transaction_id`.

### Objetos criados pelas migrations

Ficam em [db/migration](db/migration). O schema de auditoria roda primeiro.

| Schema | Migration | O que cria |
|---|---|---|
| audit | `V1` | `SEQ_BALANCE_AUDIT` e `BALANCE_AUDIT` |
| audit | `V2` | grant de `INSERT` pro `WALLET_OWNER` |
| audit | `V3` | triggers que bloqueiam `UPDATE`, `DELETE`, `TRUNCATE` e `DROP` na auditoria |
| owner | `V1` | `SEQ_WALLET` e `SEQ_WALLET_TRANSACTION` |
| owner | `V2`, `V3` | tabelas `WALLET` e `WALLET_TRANSACTION` |
| owner | `V4`, `V5` | views de leitura e grant de `READ` pro `WALLET_APP` |
| owner | `V6` | muda as colunas de texto pra tamanho em caracteres (explico no DECISIONS.md) |
| owner | `V7` | package `PKG_WALLET` |
| owner | `V8` | triggers de imutabilidade do extrato |
| owner | `V9` | trigger de auditoria |
| owner | `V10` | grant de `EXECUTE` no package pro `WALLET_APP` |

### PKG_WALLET

Tem duas procedures:

```sql
PROCEDURE create_wallet(p_holder_name, p_holder_document,
                        o_wallet_id, o_balance, o_created_at);

PROCEDURE register_transaction(p_wallet_id, p_idempotency_key, p_operation_type, p_amount, p_description,
                               o_transaction_id, o_balance_after, o_created_at, o_replayed);
```

O package é `AUTHID DEFINER`, ou seja, roda com os privilégios do owner. É por isso que a aplicação consegue gravar mesmo sem ter grant nas tabelas.

### Concorrência e idempotência

O `register_transaction` faz tudo numa chamada só, nessa ordem:

1. Valida tipo, valor, chave e descrição.
2. Trava a carteira com `SELECT ... FOR UPDATE WAIT 5`. A partir daqui, qualquer outra operação na mesma carteira fica esperando. Carteiras diferentes não travam uma a outra. Se passar de 5 segundos esperando, sai `ORA-20201` (vira `409 WALLET_BUSY`).
3. Procura a chave de idempotência na carteira. Se achar com o mesmo payload, devolve o lançamento original (`o_replayed = 1`). Se achar com payload diferente, sai `ORA-20200`.
4. Confere o saldo. Se o débito for maior que o saldo, sai `ORA-20300`.
5. Grava o lançamento, atualiza o saldo e faz commit.

Se der erro em qualquer passo, faz rollback antes de devolver o erro pra aplicação.

Por que isso garante as três regras mesmo com várias chamadas ao mesmo tempo:

- Saldo nunca negativo: o saldo é conferido e atualizado com a carteira travada, então ninguém consegue mudar o saldo no meio do caminho. E ainda tem o `CHECK (balance >= 0)` na tabela, por garantia.
- Nada aplicado duas vezes: a busca da chave acontece depois do lock. Se duas requisições com a mesma chave chegam juntas, a segunda espera a primeira terminar e já encontra o lançamento pronto. Se mesmo assim passar alguma coisa, a constraint `UNIQUE (wallet_id, idempotency_key)` barra o segundo insert.
- Saldo sempre igual à soma do que foi aplicado: lançamento e saldo são gravados na mesma transação, ou entram os dois ou nenhum. Os testes de concorrência conferem isso no final de cada rodada (saldo, soma do extrato e auditoria).

Quem controla a transação é a procedure, não a aplicação. O Java não abre transação pra escrever: cada chamada ao package já é uma transação curta e completa, e um erro no Java não tem como deixar lock preso. O ponto negativo é que não dá pra juntar duas chamadas numa transação só (comento no DECISIONS.md).

### Sequences

- `SEQ_WALLET` (cache 20) e `SEQ_WALLET_TRANSACTION` (cache 100) geram os ids pelo `DEFAULT seq.NEXTVAL` das colunas. A `SEQ_BALANCE_AUDIT` (cache 50) gera os da auditoria.
- Com cache, o Oracle guarda um bloco de números em memória. Se o banco reiniciar, o que sobrou do bloco se perde e a numeração fica com buracos. Rollback também não devolve número.
- Ou seja: os ids sempre crescem, mas não são contínuos. Nada no sistema depende disso. O extrato ordena por `id` porque, na mesma carteira, o lock faz as operações acontecerem uma de cada vez, e a ordem dos ids acompanha essa ordem.

### Triggers

| Trigger | Schema | O que faz |
|---|---|---|
| `TRG_WALLET_TRANSACTION_IMMUTABLE` | owner | bloqueia `UPDATE` e `DELETE` no extrato (`ORA-20900`) |
| `TRG_PROTECT_LEDGER_DDL` | owner | bloqueia `TRUNCATE` e `DROP` do extrato, mesmo pelo dono |
| `TRG_WALLET_BALANCE_AUDIT` | owner | toda vez que o saldo muda, grava uma linha na auditoria |
| `TRG_BALANCE_AUDIT_IMMUTABLE` | audit | bloqueia `UPDATE` e `DELETE` na auditoria (`ORA-20901`) |
| `TRG_PROTECT_AUDIT_DDL` | audit | bloqueia `TRUNCATE` e `DROP` da auditoria |

O trigger de auditoria pega qualquer mudança de saldo, até um `UPDATE` feito direto no banco. Ele só marca como `PACKAGE` quando o saldo novo bate com um lançamento do extrato. Se não bater, marca como `DIRECT`, então uma alteração manual fica registrada.

## Estrutura do projeto

```
db/
  init/01-create-schemas.sh        cria os três usuários
  migration/audit/                 migrations do WALLET_AUDIT
  migration/owner/                 migrations do WALLET_OWNER
src/main/java/com/walletapi/
  wallet/web/                      controller e DTOs
  wallet/                          service
  wallet/persistence/              chamadas ao PKG_WALLET e leituras nas views
  wallet/domain/                   modelos
  error/                           catálogo de erros e tradução dos ORA-20xxx
  security/                        filtro da API key
  config/                          config do Swagger
src/test/java/com/walletapi/       testes unitários (*Test) e de integração (*IT)
docker-compose.yml                 Oracle e as migrations
DECISIONS.md                       decisões técnicas
```

## Configuração

A aplicação lê o `.env` da raiz sozinha. Se tiver uma variável de ambiente com o mesmo nome, ela ganha do `.env`.

| Variável | Padrão | Pra que serve |
|---|---|---|
| `WALLET_APP_PASSWORD` | obrigatória | senha do `WALLET_APP` |
| `API_KEY` | obrigatória | chave do `X-API-Key` (se ficar vazia, a aplicação não sobe) |
| `DB_URL` | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` | URL do banco |
| `DB_USERNAME` | `wallet_app` | usuário do banco |
| `DB_POOL_SIZE` | `20` | tamanho do pool de conexões |
| `SERVER_PORT` | `8080` | porta da API |

## Problemas comuns

- **O `docker compose up` reclama de variável não definida:** o `.env` não existe ou tem senha vazia (volta no passo 2).
- **Troquei as senhas no `.env` e agora dá `ORA-01017`:** os usuários só são criados na primeira vez que o banco sobe. Roda `docker compose down` e depois `docker compose up -d`.
- **O Oracle não fica healthy ou fica reiniciando:** quase sempre é memória. Confere se o Docker tem pelo menos 4 GB e olha o `docker compose logs oracle`.
- **Porta 1521 ou 8080 ocupada:** para o que estiver usando a porta. No caso da API, também dá pra mudar a porta com `SERVER_PORT=8081 ./mvnw spring-boot:run`.
- **A API não sobe e aparece `Binding to target ... ApiKeyProperties failed`:** o `API_KEY` está vazio no `.env`.
- **Tudo volta 401:** falta o `X-API-Key` ou o valor não é o mesmo do `.env`. Se mudou o `.env`, reinicia a API.
- **`./mvnw: Permission denied` no Linux ou Mac:** roda `chmod +x mvnw`.
- **O `./mvnw verify` falha nos testes `*IT`:** precisa do Docker rodando. Pra rodar só os unitários, usa `./mvnw test`.
