package styx;

import com.github.kevinsawicki.http.HttpRequest;
import com.google.common.collect.ImmutableList;
import org.bitcoin.protocols.payments.Protos;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.protocols.payments.PaymentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

public class Client {
    public final static Logger logger = LoggerFactory.getLogger(Client.class);
    public WalletAppKit kit;
    public NetworkParameters params;
    public String host;

    public static void main(String[] args) throws Exception {

        Client.logger.info("WELCOME! Let's do some serious API calls");

        String environment = System.getenv("BITCOIN_NETWORK");
        if (environment == null) {
            environment = "org.bitcoin.regtest";
        }
        logger.info("using network " + environment);

        String url = System.getenv("SERVER_BASE_URL");
        if (url == null) {
            url = "http://localhost:4567";
        }
        Client client = new Client(environment, url);

        logger.info("send money to: " + client.wallet().currentReceiveAddress().toString());

        if(client.wallet().getBalance().isZero()) {
            logger.info("wallet balance is zero. please provide some funds and try again after the funding transaction is confirmed");
        } else {

            HttpRequest request = client.request("/api", "6.230833,-75.590556");

            logger.info("request successful");
            logger.info(request.body());
        }
    }

    public Client(String environment, String host) throws Exception {
        this.host = host;
        this.params = NetworkParameters.fromID(environment);
        this.kit = new WalletAppKit(params, new File("."), "styx-clien-" + params.getPaymentProtocolId());
        kit.startAsync();
        kit.awaitRunning();
    }
    public Wallet wallet() {
        return this.kit.wallet();
    }
    public HttpRequest request(String path, String data) throws Exception {
        logger.info("requesting: " + this.host + path);
        logger.info("data: " + data);
        return this.sendRequest(this.host + path, data);
    }

    public static HttpRequest sendRequest(String url, String data, PaymentSession paymentSession, Wallet.SendRequest sendRequest, Address refundAddr, String memo) throws Exception {
        logger.info("attaching payment - hash: " + sendRequest.tx.getHashAsString());
        logger.info("data: " + data);
        Protos.Payment payment = PaymentProtocol.createPaymentMessage(ImmutableList.of(sendRequest.tx), paymentSession.getValue(), refundAddr, memo, paymentSession.getMerchantData());
        HttpRequest request = HttpRequest.post(url);

        new ByteArrayInputStream(payment.toByteArray()).toString();
        request.part("request", data);
        request.part("payment", new ByteArrayInputStream(payment.toByteArray()));

        return request;
    }

    private HttpRequest sendRequest(String url, String data) throws Exception {
        HttpRequest request = HttpRequest.post(url);
        request.part("request", data); // only the request part - for the demo no payment part for the first request

        logger.info("server HTTP response code: " + request.code());
        if (request.code() == 402) {
            logger.info("payment required");
            Protos.PaymentRequest pr = Protos.PaymentRequest.parseFrom(request.stream());
            PaymentSession session = new PaymentSession(pr, true, null);
            if(session.isExpired()) {
                logger.info("oh, request expired. might be an error on the server.");
                return null;
            } else {
                logger.info("server payment request memo: " + session.getMemo());
                logger.info("server requested amount: " + session.getValue().toFriendlyString());
                Wallet.SendRequest sendRequest = session.getSendRequest();
                this.wallet().completeTx(sendRequest);  // may throw InsufficientMoneyException

                logger.info("sending new request with payment");
                request = sendRequest(url, data, session, sendRequest, null, null);

                if(request.code() == 200) {
                    logger.info("yay, request was accepted. And the server has published our transaction");
                    this.wallet().commitTx(sendRequest.tx);
                }
                return request;
            }

        } else {
            return request;
        }

    }


}
