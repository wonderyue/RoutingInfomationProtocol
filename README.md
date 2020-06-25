## Usage

```shell
java -jar RIP-1.0-jar-with-dependencies.jar [router_id] [mode]
```

router_id:

- defined in config file
- 1 by default

mode: 

- 0: normal (default value)
- 1: split horizon
- 2: split horizon with poison reverse

The files below have to be in the same folder:

```
- RIP-1.0-jar-with-dependencies.jar
- log4j.properties
- config.txt
```

## Configuration

```json
{
    "regular_timer": 10, // query interval (seconds)
    "shutdown_timer": 120, // auto shutdown timer (seconds)
    "protocol_port": 5200, // udp port
    "routers": {
        "1": { // router_id
            "id": 1, // router_id
            "ip": "ip1", // ip
            "neighbors": [2], // router_id of neighbors
            "actions": {
                "1": "DISCONNECT" // router_1 will disconnect with its neighbors in round 1
            }
        },
        "2": {
            "id": 2,
            "ip": "ip2",
            "neighbors": [1, 3]
        },
        "3": {
            "id": 3,
            "ip": "ip3",
            "neighbors": [2, 4]
        },
        "4": {
            "id": 4,
            "ip": "ip4",
            "neighbors": [3, 5]
        },
        "5": {
            "id": 5,
            "ip": "ip5",
            "neighbors": [4]
        }
    }
}
```

## Main Idea

This RIP implementation is base on UDP. Packets are encoded by Protobuf.

- Every config.regular_timer seconds, a  router sends RIP.REQUEST to its neighbors.
- When a router receives a RIP.REQUEST, response with its Routing Table.
- When a router receives a RIP.RESPONSE:
  - if destination is the router itself, pass it.
  - if destination is not exist in the routing table, and metric is not infinite, insert into the routing table.
  - if next hop is the sender of the response message, force a update of metric.
  - if new metric is smaller, update routing table.
- To simulate disconnection:
  - DISCONNECT action is configured in config.txt, will be triggerd in certain round.
  - When DISCONNECT action is triggered, a router sends RIP.DISCONNECT to its neighbors.
  - When a router receives a RIP.DISCONNECT, it updates the metric of corresponding destination to infinite. 

## Depedencies

- [Netty](https://netty.io/): network application framework 
- [Protobuf](https://developers.google.com/protocol-buffers): binary packet serialization/deserialization
- [asciitable](https://github.com/vdmeer/asciitable): format table string
- [Gson](https://github.com/google/gson): json serialization/deserialization

## Analyses

All 3 modes are running with the same config file in which router_1 will disconnect with router_2 at round 1.  

Below shows how metric of Destination_1 changes in each routing table.

### Normal Mode

Cause the "Count To Infinity" problem.

| router_2 | router_3 | router_4 | router_4 |
| -------- | -------- | -------- | -------- |
| 1        | 2        | 3        | 4        |
| 3        | 4        | 5        | 6        |
| 5        | 6        | 7        | 8        |
| 7        | 8        | 9        | 10       |
| 9        | 10       | 11       | 12       |
| 11       | 12       | 13       | 14       |
| 13       | 14       | 15       | 16       |
| 15       | 16       | 16       | 16       |
| 16       | 16       | 16       | 16       |



### Split Horizon Mode

| router_2 | router_3 | router_4 | router_4 |
| -------- | -------- | -------- | -------- |
| 1        | 2        | 3        | 4        |
| 16       | 2        | 3        | 4        |
| 16       | 16       | 3        | 4        |
| 16       | 16       | 16       | 4        |
| 16       | 16       | 16       | 16       |



### Split Horizon wIth Poison Reverse Mode

| router_2 | router_3 | router_4 | router_4 |
| -------- | -------- | -------- | -------- |
| 1        | 2        | 3        | 4        |
| 16       | 2        | 3        | 4        |
| 16       | 16       | 3        | 4        |
| 16       | 16       | 16       | 4        |
| 16       | 16       | 16       | 16       |