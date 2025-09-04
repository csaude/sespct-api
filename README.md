# SESPCT-API

**Bridge/Middleware** entre os sistemas **SESP** e **eCT** para sincronização de **pedidos** e **aprovações de Falência Terapêutica (FT)**, com foco em integração resiliente, auditoria e observabilidade.

## ✨ Objetivos
- Receber e normalizar **pedidos de FT** provenientes do SESP.
- Encaminhar, validar e **sincronizar aprovações** com o **eCT**.
- Expor **webhooks**/endpoints seguros para eventos e confirmações.
- Garantir **idempotência**, **rastreamento** e **reprocessamento**.
- Publicar **métricas** (Micrometer/Datadog) e **logs** consistentes.

## 🏗️ Stack
- **Micronaut 4.9 (Netty)** — API REST
- **JPA/Hibernate** + **Liquibase**
- **MariaDB** (prod) / **H2** (dev)
- **JWT** (Micronaut Security)
- **Micrometer** (Datadog)
- **HikariCP** (pool de conexões)

## 🔧 Requisitos
- Java 17+
- Gradle 8+
- MariaDB 10.6+ (produção)
- Acesso aos sistemas SESP e eCT

## ⚙️ Configuração
Variáveis principais:
```bash
# Ambiente Micronaut
export MICRONAUT_ENVIRONMENTS=development   # ou production

# JWT
export JWT_GENERATOR_SIGNATURE_SECRET='troque-este-segredo'

# Base de dados (MariaDB)
export SESPCT_DB_HOST=localhost
export SESPCT_DB_PORT=port
export SESPCT_DB_USERNAME=sespct_user
export SESPCT_DB_PASSWORD=supersecreto

# Datadog (opcional)
export DATADOG_APIKEY='...'
