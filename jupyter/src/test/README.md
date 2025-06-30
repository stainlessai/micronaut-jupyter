# Test Suite Documentation

This directory contains the comprehensive test suite for the Micronaut Jupyter integration, including unit tests, integration tests, and all necessary resources for testing the Jupyter kernel functionality.

## Test Structure

### Unit Tests (`groovy/`)
Located in `src/test/groovy/ai/stainless/micronaut/jupyter/`:

- **`SimpleTest.groovy`** - Basic sanity test (1 + 1 == 2)
- **`ConfigurationTest.groovy`** - Tests configuration management and bean creation/disabling
- **`InstallKernelTest.groovy`** - Tests kernel installation, configuration, and file generation
- **`KernelEndpointTest.groovy`** - Tests the HTTP endpoint for kernel lifecycle management
- **`KernelManagerTest.groovy`** - Tests kernel management functionality

### Integration Tests (`../integrationTest/`)
Located in `src/integrationTest/groovy/ai/stainless/micronaut/jupyter/kernel/`:

- **`KernelSpec.groovy`** - Base specification class providing Docker container orchestration
- **`BasicGroovyTest.groovy`** - Tests execution of basic Groovy notebooks
- **`BeansTest.groovy`** - Tests Micronaut bean access from Jupyter notebooks
- **`LoggingTest.groovy`** - Tests logging functionality from notebooks

### Test Support Library
Located in `src/integrationTest/groovy/ai/stainless/micronaut/jupyter/logging/`:

- **`LoggingClass.groovy`** - Test class for validating external library loading in notebooks

## Running Tests

### Prerequisites
```bash
# Build required JARs
gradle assemble
```

### Unit Tests
```bash
gradle unitTest
```

### Integration Tests
```bash
gradle integrationTest
```

### Run a Single Integration Test
```bash
gradle jupyter:basicIntegrationTest --tests="LoggingTest"
```

### All Tests
```bash
gradle test
```

## Integration Test Architecture

The integration tests use a sophisticated Docker-based architecture to simulate real Jupyter-Micronaut interactions:

### Container Setup
1. **Micronaut Server Container**: Runs the example service with the Jupyter kernel
2. **Jupyter Client Container**: Runs Jupyter with nbclient to execute notebooks
3. **Shared Docker Network**: Enables communication between containers
4. **Port Forwarding**: Uses `socat` to forward ZMQ ports between containers

### Test Flow
1. **Container Startup**: 
   - Micronaut container starts with beans-service example
   - Jupyter container builds from `jupyter.Dockerfile`
   - Shared network created for inter-container communication

2. **Kernel Installation**:
   - Kernel configuration files copied to Jupyter container
   - `kernel.sh` script sets up port forwarding and health checks
   - `test_kernel.py` validates kernel connectivity

3. **Notebook Execution**:
   - Test notebooks executed using `nbclient`
   - Results captured and validated
   - Logs and outputs analyzed for correctness

### Architecture Components

#### KernelSpec Base Class
- Manages Docker container lifecycle
- Provides utility methods for notebook execution
- Handles network setup and teardown
- Validates execution results

#### Port Forwarding Strategy
- ZMQ ports (shell, iopub, stdin, control, heartbeat) forwarded via `socat`
- HTTP port (8080) accessible through Docker network
- Environment variable `JUPYTER_KERNEL_BIND_HOST=0.0.0.0` enables cross-container access

## Resources Directory Structure

### `resources/`
Configuration and test assets:

- **`application-*.yml`** - Micronaut configuration files for different test scenarios
- **`jupyter.Dockerfile`** - Dockerfile for building Jupyter test container
- **`logback-test.xml`** - Logging configuration for tests
- **`test-startup.sh`** - Container startup script for Micronaut service

### `resources/kernels/micronaut/`
Jupyter kernel definition:

- **`kernel.json`** - Jupyter kernel specification
- **`kernel.sh`** - Kernel startup script with port forwarding and health checks
- **`test_kernel.py`** - Python script for validating kernel connectivity

### `resources/notebooks/`
Test notebooks for various scenarios:

- **`println.ipynb`** - Basic output testing
- **`serviceMethod.ipynb`** - Micronaut service method invocation
- **`applicationContext.ipynb`** - Application context access
- **`mdRepository.ipynb`** - Database repository testing
- **`logging.ipynb`** - Logging functionality testing

### `test_temp/`
Runtime directory for test execution:

- **Beaker output directories** - Temporary directories created during test execution
- **Log files** - `nbclient.log`, `socat_*.log` files for debugging
- **Shared filesystem** - Communication channel between containers

## Key Features Tested

### Kernel Functionality
- Groovy code execution in Jupyter environment
- Micronaut dependency injection access
- Service method invocation
- Database operations
- Logging integration

### Container Communication
- ZMQ port forwarding between containers
- HTTP health check validation
- Network isolation and security
- Environment variable configuration

### Error Handling
- Connection failure scenarios
- Kernel startup validation
- Container health monitoring
- Graceful shutdown procedures

## Debugging

### Log Files
- **`test_temp/nbclient.log`** - Jupyter notebook execution logs
- **`test_temp/socat_*.log`** - Port forwarding logs for each ZMQ channel
- **Container logs** - Available via `docker logs <container_id>`

### Common Issues
1. **Port conflicts** - Ensure no other services using test ports
2. **Container networking** - Check Docker network configuration
3. **JAR dependencies** - Verify `gradle assemble` completed successfully
4. **Kernel connectivity** - Review `test_kernel.py` output for connection issues

### Manual Testing
```bash
# View container logs
docker logs <container_name>

# Execute commands in containers
docker exec -it <container_name> bash

# Check port forwarding
netstat -tlnp | grep <port>
```

## Dependencies

### Test Libraries
- **Spock Framework** - BDD-style testing framework
- **Testcontainers** - Docker container management for integration tests
- **Jackson** - JSON processing for test data validation

### Container Tools
- **Docker** - Container runtime
- **socat** - Port forwarding utility
- **jq** - JSON processing in shell scripts
- **curl** - HTTP client for health checks

### Python Tools (in Jupyter container)
- **jupyter_client** - Jupyter protocol client
- **nbclient** - Notebook execution engine