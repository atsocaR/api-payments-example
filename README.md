## Bitcoin based API payments

This example shows how Bitcoin payments for API requests can be implemented.  
Basically a computer program pays another program to use its data. 


## Idea

API providers typically require you to signup with your credit card before using their API. They give you an API key to authenticate your request and depending on your usage your card gets debited. 
Bitcoin allows us to directly transfer funds digitally. The idea here is to attach a bitcoin transaction directly to your API call. Imagine sending an envelope with a letter and adding some cash.
Because no signup is needed anymore this also allows machines to directly interact and do business with each other.

This example consists of two parts. A server that exposes an API endpoint (to get the current weather at a certain lat/lng), and a client that requests the API and if needed sends a Bitcoin transaction with the request. 

### Sequence diagram

![Sequence diagram](https://raw.githubusercontent.com/bumi/api-payments-example/master/sequence-explanation.png)

### Problems

Each Bitcoin transaction has some costs to it. Depending on the kind of API this is probably to high. One solution for this is the use of [micro-channel transaction](https://bitcoinj.github.io/working-with-micropayments). 
If time allows and if you are interested I might add an example for that some time. 

Request overhead. There is an additional overhead for validating and publishing the transaction which probably causes a bit slower respones times.


## Server

The server exposes an endpoint to request weather information for given coordinates. If no payment is attached it responds with a [HTTP 402](https://http.cat/402) and a [BIP70 payment request](https://github.com/bitcoin/bips/blob/master/bip-0070.mediawiki).
In this example we accept the payment as part of a HTTP multipart request. The client sends a payment including the transactions to fulfill the requested payment (as described in BIP70). And along with the payment the acutal request.
If the server accepts the payment and if it can fulfill the request it broadcasts the transactions to the Bitcoin network and resonds with the requested data (here the weather information).

## Client

The client requests the endpoint. If the server responds with a HTTP 402 payment required status code it parses the payment request, creates a transactions and resends the request along with the payment. 

([Here](http://share-michaelbumann-com.s3-website-eu-west-1.amazonaws.com/screenshots/styx-api-example-request-debug.jpg) is a [dump of the client request](http://share-michaelbumann-com.s3-website-eu-west-1.amazonaws.com/screenshots/styx-api-example-request-debug.jpg) including the payment.)

## Setup

I've simply run the server and the client from within my IntelliJ IDE.

### Server

The server requires the following ENV variables to be set: 

* BITCOIN_NETWORK: org.bitcoin.regtest, org.bitcoin.testnet, org.bitcoin.production (defaults to org.bitcoin.regtest) 
* WATCHING_KEY: the wallet watching key to generate recipient addresses
* PRICE: the price per API call (defaults to 100000)
* FORECASTIO_KEY: [forcast.io API key](https://developer.forecast.io/) for requesting weather data

because ther server is only receiving money the wallet does not require to hold any funds. 

#### Package a jar and run

    $ mvn package
    $ [ENV variables] java -jar target/StyxServer-jar-with-dependencies.jar

### Client

The client can be configured with the following ENV variables:

* BITCOIN_NETWORK: org.bitcoin.regtest, org.bitcoin.testnet, org.bitcoin.production (defaults to org.bitcoin.regtest) 
* SERVER_BASE_URL: the URL of the server (defaults to http://localhost:4567) 

Because the client needs to sign transactions it requires a wallet WITH funds. The example creates a new wallet file and tells you the funding address. Please make sure that the funding transaction has some confirmation. 

#### Package a jar and run

    $ mvn package
    $ [ENV variables] java -jar target/StyxClient-jar-with-dependencies.jar

### Test it!

I've deployed a server running in Testnet on [styx-api-demo.herokuapp.com](https://styx-api-demo.herokuapp.com) (might be a bit slow because heroku has to boot).   
You can test and play with it there. Write your own client (maybe in another language?) or configure the [Client](https://github.com/bumi/api-payments-example/blob/master/src/main/java/styx/Client.java) to use that server: `SERVER_BASE_URL=https://styx-api-demo.herokuapp.com` (note: no / at the end) and `BITCOIN_NETWORK=org.bitcoin.test`

    $ mvn package && SERVER_BASE_URL=https://styx-api-demo.herokuapp.com BITCOIN_NETWORK=org.bitcoin.test java -jar target/StyxClient-jar-with-dependencies.jar

Running this for the first time will create a new wallet for you and sync the blockchain (no worries it is a SPV wallet so the chain sync only takes a minute or two).  
Then it will tell you to send some testnet(!) bitcoins to an address (look closely in the output as there is a lot of debug output. look for lines starting with: `INFO styx.Client`).  
Once done that and the transaction got confirmed run it again and it should give you the response from the server - a JSON string with the current weather of Medellín, Colombia (again maybe a bit hidden in the logs). (hint: it is probably nice weather in Medellín :D )  
After a view seconds (might take a bit on the testnet) you should find the transaction (look for the transaction hash in the logs) in your favorit testnet block explore (maybe [blockcypher](https://live.blockcypher.com/btc-testnet/)).

If you do not have any testnet coins you can get some from here: [tpfaucet.appspot.com](https://tpfaucet.appspot.com) or [faucet.xeno-genesis.com](http://faucet.xeno-genesis.com).

[Here](http://share-michaelbumann-com.s3-website-eu-west-1.amazonaws.com/screenshots/styx-api-example-request-debug.jpg) is a request.bin [dump of the client request](http://share-michaelbumann-com.s3-website-eu-west-1.amazonaws.com/screenshots/styx-api-example-request-debug.jpg) to the server including the payment.

## Have questions? What do you think? 

I would love to hear your thoughts on this. What are your ideas?

follow the [related blog post](http://michaelbumann.com/post/141356263577/experimenting-with-bitcoin-machine-to-machine)


------------

Contact: hello@michaelbumann.com  
Blog: [michaelbumann.com](http://michaelbumann.com)  
Twitter: [@bumi](http://twitter.com/bumi)


