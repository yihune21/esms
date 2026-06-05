# NIC eSMS Platform Implementation Walkthrough

The backend architecture for the Enterprise SMS Communication Platform (ESCP-2026) has been fully implemented according to the High-Level Design (HLD) and Low-Level Design (LLD) specifications.

## What Was Completed

We successfully built a **Modular Monolith (eSMS-Core)** combined with an **Asynchronous Microservice (eSMS-Sender)**.

### 1. Database & Infrastructure

- Set up a full local development stack via `docker-compose.dev.yml` including **PostgreSQL 15**, **Redis 7**, and **RabbitMQ 3.12**.
- Engineered a robust, schema-driven initialization using **Flyway**. Seven migration scripts (`V001` through `V007`) create the complete schema for workspaces, campaigns, RBAC roles, contact lists, the transactional outbox, and approval triggers.

### 2. Core Security & Identity

- Implemented a secure **Stateless JWT** architecture using HMAC-SHA (ready to be swapped for RSA in production).
- Engineered the **OTP Service** and **Session Service** using Redis with a sliding 5-minute idle timeout window.
- Implemented brute-force protection via the **Lockout Service** (IP and Username tracking).
- Created the **WorkspaceContext Filter**, which injects tenant boundaries into every authenticated request based on the JWT payload.

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
> 1. Start the backing infrastructure: `docker-compose -f docker-compose.dev.yml up -d`
> 2. Build the Docker images using the provided `Dockerfile`s in `esms-core` and `esms-sender`. These files use a multi-stage build that compiles the code safely inside a JDK 17 environment.
> 3. Run the compiled Docker containers, and they will automatically connect to the local Postgres/Redis/RabbitMQ stack.

## Next Steps

1. **Environment Configuration**: Before deploying or running locally, make sure to create a `.env` file in the root directory (copied from the configuration format) and populate `NIB_SMSC_PASSWORD` with the real SMPP credentials. Ensure this file is never committed to Git.
2. **Network Reachability**: The sender microservice must be deployed on a server that has network reachability to the NIB internal SMSC (`10.204.181.70:5019`).
3. **Active Directory Binding**: The `AuthController` currently stubs local DB authentication but is architected to be swapped with an LDAP bind once the NIC IT department provisions the service account.
