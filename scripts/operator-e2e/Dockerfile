# The e2e's client: python and pika, nothing else. Built and loaded into the
# throwaway kind cluster by scripts/operator-e2e.sh.
FROM python:3.12-alpine
RUN pip install --no-cache-dir pika==1.3.2
COPY client.py /client.py
USER 65532:65532
ENTRYPOINT ["python", "-u", "/client.py"]
