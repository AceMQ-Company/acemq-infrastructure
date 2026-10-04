"""The e2e's hands on the two brokers: publish, consume, count, purge.

Run inside the cluster (the client image), with BLUE_USERNAME, BLUE_PASSWORD,
GREEN_USERNAME and GREEN_PASSWORD from the RabbitMQ Cluster Operator's
default-user Secrets. Depth is read over AMQP with a passive declare, which is
exact now, rather than from the management API, which is up to five seconds old.
"""
import base64
import json
import os
import sys
import time
import urllib.request

import pika

NAMESPACE = os.environ.get("E2E_NAMESPACE", "acemq-infra-e2e")


def params(cluster):
    user = os.environ[cluster.upper() + "_USERNAME"]
    password = os.environ[cluster.upper() + "_PASSWORD"]
    return pika.ConnectionParameters(
        host=f"{cluster}.{NAMESPACE}.svc", port=5672,
        credentials=pika.PlainCredentials(user, password), heartbeat=30)


def management(cluster, path):
    user = os.environ[cluster.upper() + "_USERNAME"]
    password = os.environ[cluster.upper() + "_PASSWORD"]
    request = urllib.request.Request(f"http://{cluster}.{NAMESPACE}.svc:15672/api{path}")
    token = base64.b64encode(f"{user}:{password}".encode()).decode()
    request.add_header("Authorization", "Basic " + token)
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def declare(cluster, queue):
    with pika.BlockingConnection(params(cluster)) as connection:
        connection.channel().queue_declare(queue, durable=True)


def publish(cluster, queue, count):
    with pika.BlockingConnection(params(cluster)) as connection:
        channel = connection.channel()
        channel.queue_declare(queue, durable=True)
        channel.confirm_delivery()
        for number in range(int(count)):
            channel.basic_publish("", queue, f"message {number}".encode(),
                                  pika.BasicProperties(delivery_mode=2), mandatory=True)
    print(f"published={count}")


def consume(cluster, queue):
    """Acks slowly and keeps count, until the cutover closes the connection."""
    acked = 0
    try:
        connection = pika.BlockingConnection(params(cluster))
        channel = connection.channel()
        channel.basic_qos(prefetch_count=5)
        for method, _, _ in channel.consume(queue, inactivity_timeout=600):
            if method is None:
                break
            time.sleep(0.05)
            channel.basic_ack(method.delivery_tag)
            acked += 1
            if acked % 25 == 0:
                print(f"acked={acked}", flush=True)
    except Exception as closed:  # the cutover closing us is the expected way out
        print(f"closed: {closed!r}", flush=True)
    print(f"final acked={acked}", flush=True)


def depth(cluster, queue):
    """messages consumers, or 'absent'."""
    try:
        with pika.BlockingConnection(params(cluster)) as connection:
            ok = connection.channel().queue_declare(queue, passive=True)
            print(ok.method.message_count, ok.method.consumer_count)
    except pika.exceptions.ChannelClosedByBroker:
        print("absent")


def purge(cluster, queue):
    try:
        with pika.BlockingConnection(params(cluster)) as connection:
            connection.channel().queue_purge(queue)
    except pika.exceptions.ChannelClosedByBroker:
        pass


def shovels(cluster):
    print(len(management(cluster, "/shovels")))


if __name__ == "__main__":
    command, *arguments = sys.argv[1:]
    globals()[command](*arguments)
