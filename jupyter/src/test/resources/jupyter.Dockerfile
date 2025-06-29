FROM jupyter/base-notebook
COPY ./notebooks/ /notebooks
USER root
RUN apt update && apt install -y iptables iproute2 curl jq dos2unix dnsutils iputils-ping net-tools

# Create the jupyter kernel directory structure
RUN mkdir -p /tmp/test-location/jupyter/kernels/micronaut

# Copy kernel configuration files
COPY ./kernel.json /tmp/test-location/jupyter/kernels/micronaut/kernel.json
COPY ./kernel.sh /tmp/test-location/jupyter/kernels/micronaut/kernel.sh

# Make kernel script executable and fix line endings
RUN chmod +x /tmp/test-location/jupyter/kernels/micronaut/kernel.sh && \
    dos2unix /tmp/test-location/jupyter/kernels/micronaut/kernel.sh || true

# Set the JUPYTER_PATH environment variable
ENV JUPYTER_PATH=/tmp/test-location/jupyter

CMD sleep 300
