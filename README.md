# DentistDSS Microservices

A microservices-based Dentist Decision Support System with centralized JWT authentication.

**Live Demo**: [https://dentist.mizhifei.press/](https://dentist.mizhifei.press/)
**Repository**: [https://github.com/zm377/dentistdss-microservices](https://github.com/zm377/dentistdss-microservices)

## Architecture Overview

Centralized security architecture where API Gateway handles JWT validation while microservices focus on business logic.

## Services (11 microservices + 2 databases)

### Databases
- **PostgreSQL (v17)**: Port 5432 - User accounts, clinic data, patient records
- **MongoDB (v7.0)**: Port 27017 - AI conversations, audit logs

### Infrastructure Services
- **Config Server**: Port 8888 - Centralized configuration management
- **Discovery Server (Eureka)**: Port 8761 - Service discovery and registration
- **API Gateway**: Port 443/8080 - JWT validation, routing, HTTPS termination

### Core Business Services
- **Auth Service**: Port 8081 - JWT tokens, OAuth2, user management
- **Clinic Service**: Port 8083 - Clinic operations, staff, appointments
- **Patient Service**: Port 8085 - Patient profiles, medical history
- **System Service**: Port 8086 - System configuration, user roles

### Other Services
- **Notification Service**: Port 8088 - Email, SMS, push notifications
- **GenAI Service**: Port 8084 - AI chatbots, OpenAI integration
- **Audit Service**: Port 8087 - Activity logging, compliance
- **Admin Server**: Port 9090 - Service monitoring, health checks

## Quick Start

### Prerequisites
- Docker and Docker Compose
- JDK 25 (for local development)
- Maven

### Local Development
```bash
# Start all services locally
./start-local.sh

# Stop all services
./stop-local.sh

# Start only databases
docker-compose up -d postgres mongo
```

### Docker Deployment
```bash
# Start all services in Docker
./start-docker.sh

# Stop services (preserves databases)
./stop-docker.sh

# Check service status
./service-status.sh

# Stop everything including databases
docker-compose down -v
```

## Available Scripts

| Script | Purpose |
|--------|---------|
| `start-local.sh` | Start all services locally |
| `stop-local.sh` | Stop local services |
| `start-docker.sh` | Start services in Docker |
| `stop-docker.sh` | Stop Docker services |
| `service-status.sh` | Check service status |
| `test-gateway-security.sh` | Test API security |
| `build-and-push.sh [version]` | Build and push images |
| `deploy.sh [version]` | Deploy to production |

## Security Testing

```bash
# Test API Gateway security
./test-gateway-security.sh

# Manual tests
curl http://localhost:8080/api/clinic/list/all  # Public endpoint
curl http://localhost:8080/api/clinic/1/patients  # Should return 401
curl -H "Authorization: Bearer TOKEN" http://localhost:8080/api/clinic/1/patients
```

## Service Endpoints

| Service | Port | URL |
|---------|------|-----|
| API Gateway | 443/8080 | https://api.mizhifei.press |
| Config Server | 8888 | http://localhost:8888 |
| Eureka | 8761 | http://localhost:8761 |
| Auth Service | 8081 | http://localhost:8081 |
| Clinic Service | 8083 | http://localhost:8083 |
| GenAI Service | 8084 | http://localhost:8084 |
| Patient Service | 8085 | http://localhost:8085 |
| System Service | 8086 | http://localhost:8086 |
| Audit Service | 8087 | http://localhost:8087 |
| Notification Service | 8088 | http://localhost:8088 |
| Admin Server | 9090 | http://localhost:9090 |
| PostgreSQL | 5432 | localhost:5432 |
| MongoDB | 27017 | localhost:27017 |

## Security Architecture

### Centralized JWT Authentication
API Gateway handles all JWT validation and authorization. Business services receive user context via HTTP headers.

**Flow**: Client → API Gateway → JWT Validation → Headers → Business Service

### Roles
| Role | Access | Filtering |
|------|--------|-----------|
| SYSTEM_ADMIN | All endpoints | None |
| CLINIC_ADMIN | Clinic-specific | Own clinic |
| RECEPTIONIST | Clinic-specific | Own clinic |
| DENTIST | Clinical data | Clinic-based |
| PATIENT | Own records | Own data |

### Public Endpoints (No Auth Required)
- Auth: `/api/auth/login`, `/api/auth/signup`, `/oauth2/*`
- Clinic: `/api/clinic/list/all`, `/api/clinic/search`
- AI Chatbots: `/api/genai/chatbot/*`
- Health: `/actuator/health`

### User Context Headers
```http
X-User-ID: 12345
X-User-Email: user@example.com
X-User-Roles: CLINIC_ADMIN,DENTIST
X-Clinic-ID: 67890
```

## Development

### Adding Protected Endpoints
1. Update `JwtAuthenticationFilter.java` in API Gateway
2. Use `UserContextUtil` in service controllers:

```java
@GetMapping("/my-endpoint")
public ResponseEntity<?> myEndpoint(HttpServletRequest request) {
    String userId = UserContextUtil.getUserId(request);
    List<String> roles = UserContextUtil.getUserRoles(request);
    // Business logic
}
```

### Running Individual Services
```bash
# Local development
cd <service-name>
../mvnw spring-boot:run

# Docker development
docker-compose up --build -d <service-name>
```

### Environment Variables
```bash
GOOGLE_CLIENT_ID=your_google_client_id
GOOGLE_CLIENT_SECRET=your_google_client_secret
OPENAI_API_KEY=your_openai_api_key
```

## Production Deployment

```bash
# Build and push images
./build-and-push.sh [version]

# Deploy to production
./deploy.sh [version]
```

Images published to Docker Hub: `zm377/dentistdss-microservices:<service>-<tag>`

## Recent Updates

### OAuth Service Consolidation
- Merged OAuth-Service into Auth-Service (reduced from 2 to 1 authentication service)
- Improved performance with direct method calls instead of HTTP requests
- Simplified deployment and maintenance

### Architecture Benefits
- **Centralized Security**: All JWT validation in API Gateway
- **Clean Separation**: Business services focus on domain logic
- **Better Performance**: Reduced JWT parsing overhead
- **Easy Maintenance**: Security updates only in API Gateway

## Enhanced Features

### 1. Anonymous User Tracking
- Secure session management across microservices
- Identity stitching when users authenticate
- Unified session identifier for anonymous and authenticated users

### 2. Prompt Orchestration
- Dynamic prompt templates with MongoDB storage
- Role-based and clinic-specific customization
- Template variable substitution

### 3. AI Provider Switching
- Multi-provider support (OpenAI, Google Vertex AI)
- Automatic failover and load balancing
- Configuration-driven provider management

## Configuration

### AI Provider Configuration
```bash
# Required
OPENAI_API_KEY=your_openai_api_key

# Optional (Vertex AI)
VERTEX_AI_ENABLED=false
VERTEX_AI_PROJECT_ID=your_gcp_project_id
VERTEX_AI_LOCATION=us-central1
```

## Testing

```bash
# Run tests
./mvnw test -pl api-gateway,genai-service

# Test API endpoints
curl -X POST http://localhost:8080/api/genai/chatbot/help \
  -H "Content-Type: application/json" \
  -d '"What are your clinic hours?"'
```

## Documentation

For detailed information:
- **[ARCHITECTURE_GUIDE.md](ARCHITECTURE_GUIDE.md)** - Comprehensive architecture guide
- **[OAUTH_CONSOLIDATION_SUMMARY.md](OAUTH_CONSOLIDATION_SUMMARY.md)** - OAuth consolidation details
- **[VERIFICATION_CHECKLIST.md](VERIFICATION_CHECKLIST.md)** - Testing and verification guide

