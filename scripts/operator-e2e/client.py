"""The e2e's hands on the two brokers: publish, consume, count, list, purge.

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
    """Handles slowly and keeps the ids, until the cutover closes the connection.

    An id is recorded once it is handled, before its ack is sent. Once the
    broker has sent connection.close it discards every frame but close-ok, so
    the ack for the message in hand when the cutover closes us is thrown away
    and that message, already handled, is requeued and moved to green. That is
    the atLeastOnce duplicate the e2e counts, so the ids are what it compares;
    counting acks pika let through hid it whenever pika saw the close first.
    """
    handled = []
    try:
        connection = pika.BlockingConnection(params(cluster))
        channel = connection.channel()
        channel.basic_qos(prefetch_count=5)
        for method, _, body in channel.consume(queue, inactivity_timeout=600):
            if method is None:
                break
            time.sleep(0.05)
            handled.append(int(body.split()[-1]))
            channel.basic_ack(method.delivery_tag)
            if len(handled) % 25 == 0:
                print(f"handled={len(handled)}", flush=True)
    except Exception as closed:  # the cutover closing us is the expected way out
        print(f"closed: {closed!r}", flush=True)
    print(f"final handled={len(handled)}", flush=True)
    print(f"final ids={json.dumps(handled)}", flush=True)


def ids(cluster, queue):
    """The ids on a queue, as JSON, left where they are: got unacked, requeued on close."""
    found = []
    with pika.BlockingConnection(params(cluster)) as connection:
        channel = connection.channel()
        while True:
            method, _, body = channel.basic_get(queue)
            if method is None:
                break
            found.append(int(body.split()[-1]))
    print(json.dumps(found))


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
