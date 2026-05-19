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
- Implemented the `SmsGateway` interface and a `DummySmsGateway` that simulates sending SMS to carriers by writing to local logs, allowing full end-to-end testing without incurring carrier costs.
- Configured it to push Delivery Reports (DLRs) back into `sms.dlr.q` for the core system to update statuses.

## Local Execution Environment

Because your local machine runs a cutting-edge Java version (JDK 25) which currently conflicts with the Lombok annotation processor, we have structured the project to run entirely via Docker.

> [!TIP]
> **Running the Platform**
>
> 1. Start the backing infrastructure: `docker-compose -f docker-compose.dev.yml up -d`
> 2. Build the Docker images using the provided `Dockerfile`s in `esms-core` and `esms-sender`. These files use a multi-stage build that compiles the code safely inside a JDK 17 environment.
> 3. Run the compiled Docker containers, and they will automatically connect to the local Postgres/Redis/RabbitMQ stack.

## Next Steps

1. **Frontend Integration**: The REST APIs (Auth, Workspaces, Campaigns) are ready for the Vue/React presentation layer to connect.
2. **Carrier Implementations**: Once Ethio Telecom and Safaricom provide their SMPP/HTTP API credentials, new classes implementing `SmsGateway` can be seamlessly added to the Sender microservice.
3. **Active Directory Binding**: The `AuthController` currently stubs local DB authentication but is architected to be swapped with an LDAP bind once the NIC IT department provisions the service account.
