# Spring Security & JWT Authentication Guide (`securityex`)

A comprehensive hands-on learning project exploring **Spring Security 6+ / Spring Boot 3+ (and 4.x milestone)**, focusing on authentication, authorization, custom filter chains, database-backed user management with Spring Data JPA and PostgreSQL, password hashing with BCrypt, and stateless JWT (JSON Web Token) authentication.

---

## Table of Contents
1. [Project Overview](#project-overview)
2. [Spring Security Core Concepts Explained](#spring-security-core-concepts-explained)
   - [1. Authentication vs Authorization](#1-authentication-vs-authorization)
   - [2. The Filter Chain Architecture](#2-the-filter-chain-architecture)
   - [3. SecurityContext & SecurityContextHolder](#3-securitycontext--securitycontextholder)
   - [4. AuthenticationManager & DaoAuthenticationProvider](#4-authenticationmanager--daoauthenticationprovider)
   - [5. UserDetails & UserDetailsService](#5-userdetails--userdetailsservice)
   - [6. Password Encoding with BCrypt](#6-password-encoding-with-bcrypt)
   - [7. Stateful Sessions vs Stateless API (SessionCreationPolicy.STATELESS)](#7-stateful-sessions-vs-stateless-api-sessioncreationpolicystateless)
   - [8. CSRF (Cross-Site Request Forgery) Protection](#8-csrf-cross-site-request-forgery-protection)
3. [JWT (JSON Web Token) Deep Dive](#jwt-json-web-token-deep-dive)
   - [What is a JWT?](#what-is-a-jwt)
   - [Structure of a JWT](#structure-of-a-jwt)
   - [Why JWT for Stateless REST APIs?](#why-jwt-for-stateless-rest-apis)
4. [Step-by-Step Architecture & Request Flows](#step-by-step-architecture--request-flows)
   - [Flow 1: User Registration (`POST /users/register`)](#flow-1-user-registration-post-usersregister)
   - [Flow 2: User Login & JWT Generation (`POST /users/login`)](#flow-2-user-login--jwt-generation-post-userslogin)
   - [Flow 3: Authenticated Request with JWT (`GET /students`)](#flow-3-authenticated-request-with-jwt-get-students)
5. [Codebase Walkthrough & Component Explanations](#codebase-walkthrough--component-explanations)
   - [`SecurityConfigs.java` (Security Configuration)](#securityconfigsjava-security-configuration)
   - [`JwtFilter.java` (Custom Authentication Filter)](#jwtfilterjava-custom-authentication-filter)
   - [`JWTService.java` (Token Generation & Validation)](#jwtservicejava-token-generation--validation)
   - [`MyUserDetailsService.java` & `UserPrincipal.java` (Custom User Details)](#myuserdetailsservicejava--userprincipaljava-custom-user-details)
   - [`UserService.java` & `UserController.java` (User Registration & Login)](#userservicejava--usercontrollerjava-user-registration--login)
   - [`StudentController.java` & `HomeController.java` (Protected Resources)](#studentcontrollerjava--homecontrollerjava-protected-resources)
   - [`Users.java` (Entity Model)](#usersjava-entity-model)
6. [API Reference & Usage Examples](#api-reference--usage-examples)
7. [Prerequisites & Setup Guide](#prerequisites--setup-guide)
8. [Key Takeaways & Best Practices](#key-takeaways--best-practices)

---

## Project Overview

When building modern web applications and REST APIs, securing endpoints and verifying user identity are critical requirements. Spring Security is the de facto standard security framework for Spring-based applications.

This project was built to master the internal workings of Spring Security by replacing default in-memory behavior with a robust, production-style setup:
- **Custom Authentication Filter (`JwtFilter`)**: Intercepts HTTP requests and validates Bearer tokens.
- **Stateless Session Management**: Eliminates server-side HTTP sessions for scalable REST APIs.
- **Database-Backed Authentication**: Retrieves users and credentials from PostgreSQL via Spring Data JPA.
- **Cryptographic Password Hashing**: Protects credentials with `BCryptPasswordEncoder` (12 rounds of hashing).
- **JWT (JJwt 0.12.6)**: Implements HMAC-SHA256 token generation, claims resolution, expiration checks, and token verification.

---

## Spring Security Core Concepts Explained

### 1. Authentication vs Authorization
- **Authentication ("Who are you?")**: The process of validating a user's identity (e.g., verifying that the username and password match database records, or that a presented JWT is valid and signed).
- **Authorization ("What are you allowed to do?")**: The process of checking permissions or authorities (roles) to determine whether an authenticated user has access to a requested endpoint or operation.

### 2. The Filter Chain Architecture
Spring Security sits as a series of Servlet Filters (the `SecurityFilterChain`) positioned in front of your Spring MVC Controllers:

```
HTTP Request
     │
     ▼
┌────────────────────────────────────────────────────────┐
│               SecurityFilterChain                      │
│                                                        │
│   [CorsFilter]                                         │
│        │                                               │
│        ▼                                               │
│   [CsrfFilter] (Disabled for stateless JWT APIs)       │
│        │                                               │
│        ▼                                               │
│   [JwtFilter] (Custom OncePerRequestFilter) ───► Validates Bearer Token
│        │                                         & populates SecurityContext
│        ▼                                               │
│   [UsernamePasswordAuthenticationFilter]               │
│        │                                               │
│        ▼                                               │
│   [AuthorizationFilter] ───► Checks permissions/rules  │
└────────────────────────────────────────────────────────┘
     │
     ▼
[Spring DispatcherServlet & Controllers] (e.g., StudentController)
```

Every incoming request traverses this chain. Each filter has a single responsibility and can either:
1. Allow the request to pass to the next filter (`filterChain.doFilter(request, response)`).
2. Authenticate the request by setting an `Authentication` token in the `SecurityContext`.
3. Reject the request with an error code (such as HTTP 401 Unauthorized or HTTP 403 Forbidden).

### 3. SecurityContext & SecurityContextHolder
- **`SecurityContextHolder`**: The central repository where Spring Security stores details of the currently authenticated principal. It uses `ThreadLocal` storage by default, meaning each request thread has its own isolated security context.
- **`SecurityContext`**: Holds the `Authentication` object representing the current user.
- **`Authentication`**: Contains the principal (`UserDetails`), credentials (usually nullified after authentication), and authorities (`Collection<GrantedAuthority>`).

```java
// How an authenticated token is stored in the SecurityContext:
SecurityContextHolder.getContext().setAuthentication(authToken);

// How Spring Security reads the current user:
Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
```

### 4. AuthenticationManager & DaoAuthenticationProvider
Spring Security decouples the authentication workflow using distinct modular interfaces:

```
                    ┌────────────────────────┐
                    │  AuthenticationManager │ (Defines authenticate() contract)
                    └───────────┬────────────┘
                                │ delegates to
                                ▼
                    ┌────────────────────────────┐
                    │ DaoAuthenticationProvider  │
                    └──────┬──────────────┬──────┘
                           │              │
        Loads user details │              │ Verifies raw vs hashed password
                           ▼              ▼
           ┌──────────────────────┐   ┌───────────────────────────┐
           │  UserDetailsService  │   │   BCryptPasswordEncoder   │
           └──────────────────────┘   └───────────────────────────┘
```

- **`AuthenticationManager`**: The main entry point for processing authentication requests. When `authenticationManager.authenticate(token)` is called, it iterates through its registered providers.
- **`DaoAuthenticationProvider`**: A built-in provider that leverages a `UserDetailsService` to fetch user details from the database and a `PasswordEncoder` to compare hashed passwords.

### 5. UserDetails & UserDetailsService
Spring Security does not make assumptions about your database schema or entity structure. Instead, it defines two interfaces:
- **`UserDetails`**: Represents the user principal in Spring Security terms (username, password, granted authorities, account lock/expiration status). In this project, `UserPrincipal` wraps the JPA entity `Users`.
- **`UserDetailsService`**: A single-method contract (`loadUserByUsername(String username)`) implemented by `MyUserDetailsService` to fetch users by username from `UserRepository`.

### 6. Password Encoding with BCrypt
Storing plain-text passwords is a severe security vulnerability.
- **`BCryptPasswordEncoder`** uses a slow, adaptive one-way hashing function based on the Blowfish cipher with automatic salt generation.
- The work factor (strength) in this project is set to `12` (`new BCryptPasswordEncoder(12)`), meaning $2^{12} = 4096$ hashing rounds, significantly mitigating brute-force and dictionary attacks.
- Because a unique salt is generated for every hash, hashing the same password twice yields completely different hash strings, yet `encoder.matches(rawPassword, encodedPassword)` can reliably verify authenticity.

### 7. Stateful Sessions vs Stateless API (`SessionCreationPolicy.STATELESS`)
- **Stateful (Default Web App)**: The server creates an `HttpSession`, issues a `JSESSIONID` cookie to the browser, and keeps authentication state in server memory.
- **Stateless (REST APIs with JWT)**: Every HTTP request must carry its own proof of authentication (the JWT in the `Authorization: Bearer <token>` header). The server never stores session state across requests (`SessionCreationPolicy.STATELESS`), allowing the API to scale horizontally across multiple instances without sticky sessions or shared session caches.

### 8. CSRF (Cross-Site Request Forgery) Protection
- **What is CSRF?** An attack where a malicious website tricks a victim's authenticated browser into executing unwanted actions on another web application (relying on automatically attached session cookies).
- **Why CSRF is Disabled for JWT APIs**: CSRF exploits automatic cookie transmission. Since JWTs are transmitted explicitly via the `Authorization` header by API clients rather than stored in automatic browser cookies, CSRF attacks cannot succeed, making it safe and standard to disable CSRF (`http.csrf(AbstractHttpConfigurer::disable)`).

---

## JWT (JSON Web Token) Deep Dive

### What is a JWT?
A JSON Web Token (RFC 7519) is a compact, URL-safe, digitally signed format used to securely transmit verifiable claims between two parties.

### Structure of a JWT
A JWT consists of three parts separated by dots (`.`): `Header.Payload.Signature`

```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJzaW1vbiIsImlhdCI6MTY5...pZC...
└────────────────┬──────────────────┘ └──────────────────────┬────────────────┘ └───────────┬───────────┘
              Header                                      Payload                          Signature
```

1. **Header**: Contains metadata about the token type and the hashing algorithm used (e.g., `{"alg": "HS256", "typ": "JWT"}`).
2. **Payload (Claims)**: Contains statements about an entity (user) and additional metadata (e.g., `sub` (subject/username), `iat` (issued at), `exp` (expiration timestamp), custom claims).
3. **Signature**: Created by taking the base64-encoded header and payload, hashing them with a secret key using the specified algorithm (HMAC-SHA256):
   $$\text{Signature} = \text{HMACSHA256}(\text{base64UrlEncode}(\text{Header}) + "." + \text{base64UrlEncode}(\text{Payload}), \text{SecretKey})$$

> **Important**: The payload is base64-encoded, NOT encrypted. Anyone can decode and read its contents, but no one can alter it without invalidating the cryptographic signature.

### Why JWT for Stateless REST APIs?
- **Self-contained**: Contains all necessary identity claims, reducing redundant database lookups on every request.
- **Decentralized verification**: Any microservice sharing the secret key (or public key) can verify the token independently.
- **No server memory overhead**: Tokens are stored client-side (e.g., in memory or local storage).

---

## Step-by-Step Architecture & Request Flows

### Flow 1: User Registration (`POST /users/register`)

```
Client                      UserController            UserService           UserRepository           PostgreSQL
  │                               │                       │                       │                       │
  │── POST /users/register ──────►│                       │                       │                       │
  │   { "username": "simon",      │── registerUser(user) ─►│                      │                       │
  │     "password": "123" }       │                       │── BCrypt.encode(12) ──│                       │
  │                               │                       │   (hashes password)   │                       │
  │                               │                       │                       │                       │
  │                               │                       │── save(user) ────────►│── INSERT INTO users ─►│
  │                               │                       │◄── Saved Entity ──────│◄── User Record ───────│
  │◄── 200 OK (Saved User) ───────│◄── Return Saved User ─│                       │                       │
```

1. The client sends a JSON payload with raw username and password.
2. `SecurityConfigs` matches `/users/register` in `permitAll()` and allows the request through without authentication.
3. `UserService` hashes the raw password using `BCryptPasswordEncoder(12)`.
4. The user entity with the hashed password is saved into PostgreSQL via Spring Data JPA.

---

### Flow 2: User Login & JWT Generation (`POST /users/login`)

```
Client               UserController     UserService     AuthenticationManager   DaoAuthenticationProvider   MyUserDetailsService  JWTService
  │                        │                 │                   │                        │                         │                  │
  │── POST /users/login ──►│                 │                   │                        │                         │                  │
  │   { "username", "pwd" }│── verify(user) ─►│                  │                        │                         │                  │
  │                        │                 │── authenticate() ─►│                       │                         │                  │
  │                        │                 │                   │── authenticate() ─────►│                         │                  │
  │                        │                 │                   │                        │── loadUserByUsername() ─►│                  │
  │                        │                 │                   │                        │◄── UserPrincipal ───────│                  │
  │                        │                 │                   │                        │── (matches password) ───│                  │
  │                        │                 │                   │◄── Authenticated Token │                         │                  │
  │                        │                 │◄── Auth Success ──│                        │                         │                  │
  │                        │                 │                                                                      │                  │
  │                        │                 │── generateToken(username) ─────────────────────────────────────────────────────────────►│
  │                        │                 │◄── Returns "eyJhbGciOi..." (JWT) ────────────────────────────────────────────────────────│
  │◄── Returns JWT String ─│◄── Return Token │                                                                                         │
```

1. Client submits credentials to `/users/login` (`permitAll()`).
2. `UserService` creates an unauthenticated `UsernamePasswordAuthenticationToken` and delegates to `AuthenticationManager`.
3. `DaoAuthenticationProvider` calls `MyUserDetailsService.loadUserByUsername()`, retrieving the user and stored BCrypt hash from PostgreSQL.
4. `DaoAuthenticationProvider` verifies the submitted raw password against the BCrypt hash.
5. If valid, `UserService` calls `JWTService.generateToken()`, which signs a new JWT with HMAC-SHA256 and returns it to the client.

---

### Flow 3: Authenticated Request with JWT (`GET /students`)

```
Client                                  JwtFilter                           SecurityContextHolder         StudentController
  │                                         │                                         │                          │
  │── GET /students ───────────────────────►│                                         │                          │
  │   Header: "Authorization: Bearer <jwt>" │                                         │                          │
  │                                         │── 1. Extract Bearer Token               │                          │
  │                                         │── 2. Extract Username from Token        │                          │
  │                                         │── 3. Check if SecurityContext is empty  │                          │
  │                                         │── 4. Load UserDetails from DB           │                          │
  │                                         │── 5. Validate Token signature & expiry  │                          │
  │                                         │── 6. Create UsernamePasswordAuthToken   │                          │
  │                                         │── 7. Set Authentication in Context ────►│                          │
  │                                         │                                         │                          │
  │                                         │── 8. filterChain.doFilter() ───────────┼─────────────────────────►│
  │                                         │                                         │                          │── Execute getStudents()
  │◄── 200 OK [Students List] ──────────────┼─────────────────────────────────────────┼──────────────────────────│
```

1. Client sends an HTTP request with the header `Authorization: Bearer <token>`.
2. `JwtFilter` (positioned before `UsernamePasswordAuthenticationFilter`) intercepts the request.
3. The filter extracts the token and parses the username claim.
4. It verifies the signature and checks that the token is not expired.
5. It constructs a `UsernamePasswordAuthenticationToken` and sets it in `SecurityContextHolder.getContext().setAuthentication(authToken)`.
6. The request continues down the filter chain to `StudentController`, which successfully returns the protected resource.

---

## Codebase Walkthrough & Component Explanations

### `SecurityConfigs.java` (Security Configuration)
Located in `com.mungai_stack.securityex.configs`:
- `@Configuration` & `@EnableWebSecurity`: Marks the class as a Spring configuration and activates Spring Security web support.
- `securityFilterChain(HttpSecurity http)`:
  - Disables CSRF (`AbstractHttpConfigurer::disable`) for stateless REST communication.
  - Sets public access for `/users/login` and `/users/register` via `.permitAll()`.
  - Enforces authentication for all other requests via `.anyRequest().authenticated()`.
  - Configures `SessionCreationPolicy.STATELESS` so Spring Security does not create or rely on HTTP sessions.
  - Registers `JwtFilter` before `UsernamePasswordAuthenticationFilter` via `.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)`.
- `authenticationProvider(MyUserDetailsService userDetailsService)`: Creates and configures `DaoAuthenticationProvider` wired with `MyUserDetailsService` and `BCryptPasswordEncoder(12)`.
- `authenticationManager(AuthenticationConfiguration configuration)`: Exposes the `AuthenticationManager` bean from Spring's configuration.

### `JwtFilter.java` (Custom Authentication Filter)
Located in `com.mungai_stack.securityex.configs`:
- Extends `OncePerRequestFilter` to guarantee single execution per request dispatch.
- Checks for the `Authorization` header starting with `"Bearer "`.
- Extracts the username using `jwtService.extractUsername(token)`.
- Verifies that `SecurityContextHolder.getContext().getAuthentication() == null` (ensures we don't re-authenticate an already authenticated context).
- Validates the token against `UserDetails`.
- Builds a `UsernamePasswordAuthenticationToken` with authorities and request details (`WebAuthenticationDetailsSource`), and attaches it to the `SecurityContext`.

### `JWTService.java` (Token Generation & Validation)
Located in `com.mungai_stack.securityex.services`:
- Initializes an HMAC-SHA256 secret key dynamically using `KeyGenerator.getInstance("HmacSHA256")`.
- `generateToken(String username)`: Creates a signed token containing subject (`username`), issue time (`iat`), and expiration time (`exp`).
- `extractUsername(String token)` / `extractClaim()`: Parses token claims using `Jwts.parser().verifyWith(getKey()).build().parseSignedClaims(token)`.
- `validateToken(String token, UserDetails userDetails)`: Validates that the username in the token matches the user details and that the token is not expired.

### `MyUserDetailsService.java` & `UserPrincipal.java` (Custom User Details)
Located in `com.mungai_stack.securityex.services` and `com.mungai_stack.securityex.model`:
- `MyUserDetailsService` implements `UserDetailsService`: Queries PostgreSQL via `UserRepository.findByUsername(username)`. Throws `UsernameNotFoundException` if no user exists.
- `UserPrincipal` implements `UserDetails`: Adapts the `Users` entity to Spring Security's interface, providing username, hashed password, and granted authorities (`Collections.singleton(new SimpleGrantedAuthority("USER"))`).

### `UserService.java` & `UserController.java` (User Registration & Login)
Located in `com.mungai_stack.securityex.services` and `com.mungai_stack.securityex.controllers`:
- `POST /users/register`: Receives user details, hashes password via `BCryptPasswordEncoder`, and saves to database.
- `POST /users/login`: Authenticates credentials with `AuthenticationManager.authenticate()`, returning a generated JWT upon success or `"fail"` on failure.

### `StudentController.java` & `HomeController.java` (Protected Resources)
Located in `com.mungai_stack.securityex.controllers`:
- `GET /students` & `POST /students`: Sample endpoints protected by Spring Security requiring valid JWT authentication.
- `GET /greet`: Greets the user and demonstrates session ID inspection.
- `GET /csrf/;`: Demonstrates accessing the `CsrfToken` object from the request attribute `_csrf`.

### `Users.java` (Entity Model)
Located in `com.mungai_stack.securityex.model`:
- JPA entity mapped to the `users` table in PostgreSQL with columns `id`, `username` (unique), and `password` (hashed).

---

## API Reference & Usage Examples

### 1. Register a New User
**Endpoint:** `POST /users/register`  
**Access:** Public (`permitAll`)

```bash
curl -X POST http://localhost:8080/users/register \
  -H "Content-Type: application/json" \
  -d '{
    "id": 1,
    "username": "simon",
    "password": "mySecurePassword123"
  }'
```

---

### 2. Login to Obtain JWT Token
**Endpoint:** `POST /users/login`  
**Access:** Public (`permitAll`)

```bash
curl -X POST http://localhost:8080/users/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "simon",
    "password": "mySecurePassword123"
  }'
```

**Response:**
```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzaW1vbiIsImlhdCI6MTY5...
```

---

### 3. Access Protected Endpoint (Without Token - Expect HTTP 401 / 403)
```bash
curl -X GET http://localhost:8080/students
```

---

### 4. Access Protected Endpoint (With JWT Bearer Token)
**Endpoint:** `GET /students`  
**Access:** Authenticated

```bash
TOKEN="<paste_jwt_token_here>"

curl -X GET http://localhost:8080/students \
  -H "Authorization: Bearer $TOKEN"
```

**Response:**
```json
[
  { "id": 1, "name": "simon", "marks": 70 },
  { "id": 2, "name": "Kiran", "marks": 65 }
]
```

---

### 5. Add a New Student (Protected POST)
**Endpoint:** `POST /students`  
**Access:** Authenticated

```bash
TOKEN="<paste_jwt_token_here>"

curl -X POST http://localhost:8080/students \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": 3,
    "name": "Alex",
    "marks": 85
  }'
```

---

## Prerequisites & Setup Guide

### 1. Requirements
- **Java**: JDK 21 or later (configured for Java 25 in `pom.xml`)
- **Database**: PostgreSQL
- **Build Tool**: Maven / Maven Wrapper (`./mvnw`)

### 2. PostgreSQL Configuration
Ensure PostgreSQL is running and a database named `security` exists:

```sql
CREATE DATABASE security;
```

Update your credentials in `src/main/resources/application.properties` if necessary:
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/security
spring.datasource.username=postgres
spring.datasource.password=your_postgres_password
spring.datasource.driver-class-name=org.postgresql.Driver

spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
```

### 3. Build & Run Application
```bash
# Build the project
./mvnw clean install

# Run the Spring Boot application
./mvnw spring-boot:run
```

The server starts on default port `8080`.

---

## Key Takeaways & Best Practices

1. **Never Store Plaintext Passwords**: Always encode credentials using `BCryptPasswordEncoder` before persisting to database.
2. **Stateless APIs Must Disable CSRF**: When using JWTs sent via `Authorization` headers, disable CSRF to prevent unnecessary token validation overhead.
3. **Filter Ordering Matters**: Ensure custom token filters (`JwtFilter`) execute **before** `UsernamePasswordAuthenticationFilter` so the `SecurityContext` is populated before authentication checks occur.
4. **Keep Secrets Secure**: For production systems, externalize your JWT secret key (e.g., via environment variables or secret managers) instead of generating random in-memory keys on every restart, so tokens remain valid across server restarts and replicas.
5. **Set Expiration Times**: Always configure reasonable token expiration limits to minimize the impact of leaked tokens.
