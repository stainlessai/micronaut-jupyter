FROM jupyter/base-notebook
COPY ./notebooks/ /notebooks
USER root
RUN apt update && apt install -y iptables iproute2 curl jq
# ENV JUPYTER_PATH=/tmp/test-location/jupyter
# RUN iptables -t nat -A OUTPUT -d 127.0.0.1 -j DNAT --to-destination $(ip route | tr '\n' ' ' | awk '{print $(NF)}')
# RUN iptables -t nat -A POSTROUTING -m addrtype --src-type LOCAL --dst-type UNICAST -j MASQUERADE
CMD sleep 300
