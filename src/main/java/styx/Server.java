package styx;

import com.eclipsesource.json.JsonObject;
import com.github.dvdme.ForecastIOLib.FIOCurrently;
import com.github.dvdme.ForecastIOLib.FIODataPoint;
import com.github.dvdme.ForecastIOLib.ForecastIO;
import org.bitcoin.protocols.payments.Protos;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Spark;
import spark.utils.IOUtils;

import javax.servlet.MultipartConfigElement;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.net.InetAddress;
import java.util.List;

import static spark.Spark.before;
import static spark.Spark.get;
import static spark.Spark.post;


public class Server {
    public final static Logger logger = LoggerFactory.getLogger(Server.class.getName());
    public Wallet wallet;
    public Coin price = Coin.valueOf(100000);
    public PeerGroup peerGroup;

    public static void main(String[] args) throws Exception {
        logger.info("WELCOME! starting up");

        String environment = System.getenv("BITCOIN_NETWORK");
        if (environment == null) {
            environment = "org.bitcoin.regtest";
        }
        logger.info("using network " + environment);

        NetworkParameters params = NetworkParameters.fromID(environment);

        PeerGroup peerGroup = new PeerGroup(params);

        int numPeers;
        if (params.getPaymentProtocolId().equals("regtest")) {
            peerGroup.addAddress(new PeerAddress(InetAddress.getLocalHost(), params.getPort()));
            numPeers = 1;
        } else {
            peerGroup.addPeerDiscovery(new DnsDiscovery(params));
            numPeers = 2;
        }

        logger.info("connecting");
        peerGroup.start();
        peerGroup.waitForPeers(numPeers).get();

        String watchingKey = System.getenv("WATCHING_KEY");
        DeterministicKey key = DeterministicKey.deserializeB58(watchingKey, params);
        Wallet wallet = Wallet.fromWatchingKey(params, key);
        logger.info("connected to: " + peerGroup.getConnectedPeers().size() + " peers");
        logger.info("current receiving address: " + wallet.currentReceiveAddress().toString());

        String price = System.getenv("PRICE");
        if(price == null) {
            price = "10000";
        }
        Server server = new Server(wallet, peerGroup, Coin.valueOf(Integer.parseInt(price)));

        server.initApi();


        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                logger.info("shutting down");
                peerGroup.stop();
                logger.info("BYE");
            }
        });
    }

    public Server(Wallet wallet, PeerGroup peerGroup, Coin price) {
        this.wallet = wallet;
        this.price = price;
        this.peerGroup = peerGroup;
    }

    public void initApi() {

        String port = System.getenv("PORT");
        if (port != null) {
            Spark.port(Integer.parseInt(port));
        }

        before("/*", (request, response) -> {
            logger.info(request.requestMethod() + " " + request.pathInfo() + " ip=" + request.ip() + " real ip: " + request.headers("X-Real-IP"));
        });

        get("/", (req, res) -> {
            return "hello";
        });

        post("/api", (req, res) -> {
            if (req.raw().getAttribute("org.eclipse.jetty.multipartConfig") == null) {
                MultipartConfigElement multipartConfigElement = new MultipartConfigElement(System.getProperty("java.io.tmpdir"));
                req.raw().setAttribute("org.eclipse.jetty.multipartConfig", multipartConfigElement);
            }
            if(req.contentType() == null || !req.contentType().contains("multipart")) {
                res.status(422);
                return "please send a multipart request";
            }

            Part paymentPart = req.raw().getPart("payment");
            Part requestPart = req.raw().getPart("request");

            if(paymentPart != null) {
                logger.info("payment found");
                Payment payment = new Payment(Protos.Payment.parseFrom(paymentPart.getInputStream()), this.wallet, this.price);

                logger.info("payment value: " + payment.getSumSendToMe());
                if(payment.isValidPayment()) {
                    logger.info("payment is valid");
                    String[] data = IOUtils.toString(requestPart.getInputStream()).split(",");
                    String lat = data[0];
                    String lng = data[1];
                    Weather weather = new Weather(lat, lng, System.getenv("FORECASTIO_KEY"));
                    if(weather.hasCurrently()) {
                        payment.broadcast(this.peerGroup);
                        res.type("application/json");
                        return weather.getCurrently();
                    } else {
                        res.status(404);
                        return "not found";
                    }
                } else {
                    PaymentRequest pr = new Server.PaymentRequest();
                    return pr.generate(req, res, this.wallet, this.price).raw();
                }
            } else {
                PaymentRequest pr = new PaymentRequest();
                return pr.generate(req, res, this.wallet, this.price).raw();
            }
        });



    }

    class PaymentRequest {
        public Response generate(Request req, Response res, Wallet wallet, Coin price) throws Exception {
            res.status(402);
            res.type("application/bitcoin-paymentrequest");
            res.header("Content-Transfer-Encoding", "binary");
            res.header("Expires", "0");


            Address address = wallet.freshReceiveAddress();
            logger.info("fresh receive address: " + address.toString());
            Protos.PaymentRequest paymentRequest = PaymentProtocol.createPaymentRequest(wallet.getParams(), price, address, "API call payment is required", req.url(), null).build();

            HttpServletResponse raw = res.raw();
            paymentRequest.writeTo(raw.getOutputStream());
            raw.getOutputStream().flush();
            raw.getOutputStream().close();
            return res;
        }

    }
    class Payment {

        public Protos.Payment payment;
        public Wallet wallet;
        public List<Transaction> transactions;
        public Coin price;

        public Payment(Protos.Payment payment, Wallet wallet, Coin price) {
            this.payment = payment;
            this.wallet = wallet;
            this.price = price;
            this.transactions = PaymentProtocol.parseTransactionsFromPaymentMessage(wallet.getParams(), payment);
        }

        public void broadcast(PeerGroup peerGroup) {
            for(Transaction t : this.transactions) {
                peerGroup.broadcastTransaction(t);
                logger.info("broadcasted transaction");
            }
        }
        public Coin getSumSendToMe() {
            Coin sumSentToMe = Coin.valueOf(0);
            for(Transaction t : transactions) {
                logger.info("got transaction: " + t.toString());

                sumSentToMe = sumSentToMe.add(t.getValue(wallet));
            }
            return sumSentToMe;
        }

        public boolean isValidPayment() {
            return !this.getSumSendToMe().isLessThan(this.price);
        }

        public Protos.PaymentACK getPaymentACK() {
            Protos.Payment paymentMessage = Protos.Payment.newBuilder().build();
            Protos.PaymentACK paymentAck = PaymentProtocol.createPaymentAck(paymentMessage, "thanks");
            return paymentAck;
        }
    }

    class Weather {

        public ForecastIO forecastio;

        public Weather(String lat, String lng, String apiKey) {
            this.forecastio = new ForecastIO(lat, lng, apiKey);
        }

        public boolean hasCurrently() {
            return this.forecastio.hasCurrently();
        }

        public String getSummary() {
            if(this.hasCurrently()) {
                FIODataPoint currently = new FIOCurrently(this.forecastio).get();
                return currently.summary();
            }
            return null;
        }

        public JsonObject getCurrently() {
            if(this.hasCurrently()) {
                return this.forecastio.getCurrently();
            }
            return null;
        }

    }
}
