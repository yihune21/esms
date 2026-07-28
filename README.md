# NIC eSMS Platform Implementation Walkthrough

The backend architecture for the Enterprise SMS Communication Platform (ESCP-2026) has been fully implemented according to the High-Level Design (HLD) and Low-Level Design (LLD) specifications.

## What Was Completed

We successfully built a **Modular Monolith (eSMS-Core)** combined with an **Asynchronous Microservice (eSMS-Sender)**.

### 1. Database & Infrastructure

- Set up a full local development stack via `docker-compose.dev.yml` including **PostgreSQL 15**, **Redis 7**, and **RabbitMQ 3.12**.
- Engineered a robust, schema-driven initialization using **Flyway**. Seven migration scripts (`V001` through `V007`) create the complete schema for workspaces, campaigns, RBAC roles, contact lists, the transactional outbox, and approval triggers.

### 2. Core Security & Identity

- Implemented a secure **Stateless JWT** architecture using HMAC-SHA (ready to be swapped for RSA in production).
- Integrated **Active Directory** against the NIC domain controller (`NICDCSrv2.nibins.com:389`). Sign-in is a search-then-bind: the read-only `SMS.Service` account looks up the `sAMAccountName` by subtree search from `DC=nibins,DC=com`, then eSMS re-binds as that user's DN with the password they typed. A first successful bind provisions the local `app_user` row; AD remains the system of record for name and email. Verified end to end against the live directory.

> [!WARNING]
> **The AD link is unencrypted.** NIC does not run LDAPS — both domain controllers
> accept TCP on 636/3269 and then reset every TLS handshake, which is what a DC with
> no LDAPS certificate does. Running on plain `ldap://…:389` is a deliberate decision,
> and it means every password an LDAP simple bind carries — each user's at login, and
> the service account's on every lookup — crosses the network in readable plaintext.
> eSMS logs a `WARN` at startup whenever `app.ad.url` is not `ldaps://`.
> If IT ever installs a certificate, the only change needed is
> `AD_URL=ldaps://NICDCSrv2.nibins.com:636`.
- Sign-in is a **single step**. The platform is reachable only from the NIC LAN, so the SMS OTP that used to follow the password has been removed — along with its dependency on the SMSC being up and on every member of staff having a mobile number on file.
- Accounts Active Directory does not hold (the seeded `superadmin`) fall back to a **local BCrypt password**. Two cases are deliberately excluded from that fallback: an account AD holds and *rejects*, and any account already linked to AD (`app_user.ad_sam` set) while the domain controller is unreachable — otherwise taking the DC off the network would silently re-enable superseded local passwords across the whole system.
- Engineered the **Session Service** using Redis with a sliding 5-minute idle timeout window.
- Implemented brute-force protection via the **Lockout Service** (IP and Username tracking).
- Created the **WorkspaceContext Filter**, which injects tenant boundaries into every authenticated request based on the JWT payload.

> [!IMPORTANT]
> Passing the AD bind grants a **session, not permissions**. A newly provisioned
> account has no workspace membership and therefore no authority at all until an
> administrator assigns it a workspace and role. `POST /auth/login` reports this
> as `awaitingWorkspaceAssignment: true`.

### 3. Business Logic & Approvals

- Developed the **Workspace & Role Management** APIs.
- Implemented the strict **Campaign Approval State Machine**. The system dynamically routes campaigns through `DRAFT` -> `PENDING_APPROVAL` (or `PENDING_HEAD` -> `PENDING_CEO` for Finance) -> `APPROVED`.
- Enforced strict business rules, such as preventing drafters from approving their own campaigns.

### 4. Messaging & Orchestration

- Built the **Transactional Outbox Relay**. Campaigns that reach the "Queued" state write safely to the local database, and a polling relay guarantees delivery into RabbitMQ.
- Configured the **RabbitMQ Topology** defining the direct and topic exchanges for `sms.send`, `sms.dlr`, and Dead Letter Queues (DLQ).

### 5. eSMS Sender Microservice

- Created a lightweight standalone microservice to consume the `sms.send` queue.
- Implemented the `NibSmscSmsGateway` which connects directly to the internal NIB SMSC via SMPP 3.4. This acts as an aggregator that automatically routes to all Ethiopian carriers (Ethio Telecom and Safaricom).
- Configured native SMPP Delivery Receipts (DLRs) to be captured from `deliver_sm` packets and pushed back into `sms.dlr.q` for the core system to update statuses in real-time.
- Secured credentials by moving them entirely to environment variables via a local `.env` file.

## Local Execution Environment

we have structured the project to run entirely via Docker.

> [!TIP]
> **Running the Platform**
>
> 1. Start the backing infrastructure: `docker compose -f docker-compose.dev.yml up -d`
> 2. Build the Docker images using the provided `Dockerfile`s in `esms-core` and `esms-sender`. These files use a multi-stage build that compiles the code safely inside a JDK 17 environment.
> 3. Run the compiled Docker containers, and they will automatically connect to the local Postgres/Redis/RabbitMQ stack.

## Next Steps

1. **Environment Configuration**: Before deploying or running locally, make sure to create a `.env` file in the root directory (copied from the configuration format) and populate `NIB_SMSC_PASSWORD` with the real SMPP credentials and `AD_SERVICE_PASSWORD` with the `SMS Service` account password. Ensure this file is never committed to Git — it is already in `.gitignore`.
2. **The sender must run on a NIB-hosted server.** The SMSC at `10.204.181.70:5019` is **source-restricted**, not merely port-firewalled: connections from anywhere other than a NIB server are silently dropped — no RST, no ICMP, just a connect timeout, which is easily mistaken for a wrong host or a dead service. eSMS is allocated the LAN block `172.16.168.32/29`, and SMPP will not bind until it runs from there. Everything else — AD, Postgres, Redis, RabbitMQ, the full REST API — works off-network, so the platform stays fully developable and testable without it; only the SMPP leg is affected.
   Also: eSMS Core needs a route to the domain controller on port 389, and its container must resolve `NICDCSrv2.nibins.com` — if container DNS differs from the host's, use the IP (`10.10.130.22`) in `AD_URL` instead.
3. **Active Directory service account**: `AD_SERVICE_DN` is `nibins\SMS.Service` — the NT4 `DOMAIN\sAMAccountName` form, chosen over a DN so it survives the account being moved between OUs. Note that the account's `CN` is `SMS Service` (a space) while its `sAMAccountName` is `SMS.Service` (a dot), and it lives at `CN=SMS Service,OU=Information Technology Dept,OU=Chief Executive Officer(CEO),OU=Board Of Directors,DC=nibins,DC=com` — there is no `OU=ServiceAccounts`. If a bind ever fails, get the login name from ADUC's **"User logon name (pre-Windows 2000)"** field, never from the displayed name; this DC returns `data 52e` for both a wrong password and a missing account, so the error cannot tell them apart.
4. **Search base must stay at the domain root.** Staff live under `OU=…,OU=NIC,DC=nibins,DC=com`, a different branch from the `OU=Board Of Directors` hierarchy, and branch OUs nest several levels deep. `AD_BASE_DN=DC=nibins,DC=com` with subtree scope covers all of it; narrowing it to a single OU would silently exclude whole departments.
5. **Running without a domain controller**: Set `AD_ENABLED=false` to skip the directory entirely and authenticate against local passwords. Startup deliberately fails when AD is enabled but `AD_SERVICE_PASSWORD` is unset, rather than failing later at the first login.
