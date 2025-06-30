#
# This is the container that will run the jupyter client, it will be configured to connect to
# a server running the Micronaut kernel at localhost:8080. If that server is running in a different
# container (instead of, say, a separate process on this container, or sidecer, etc), then you'll
# need to port-forward localhost:8080 to that container (or socat, or iptables, or whatever).
#
FROM jupyter/base-notebook
COPY ./notebooks/ /notebooks
USER root
RUN apt update && apt install -y iptables iproute2 curl jq dos2unix dnsutils iputils-ping net-tools socat procps

RUN pip install --upgrade nbclient

# Create the jupyter kernel directory structure
RUN mkdir -p /usr/share/jupyter/kernels/micronaut

# Set the JUPYTER_PATH environment variable
ENV JUPYTER_PATH=/usr/share/jupyter

CMD sleep 300
