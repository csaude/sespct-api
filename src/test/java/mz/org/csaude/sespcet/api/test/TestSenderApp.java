package mz.org.csaude.sespcet.api.test;

import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;
import mz.org.csaude.sespcet.api.TestSender;
import mz.org.csaude.sespcet.api.dto.EncryptedRequestDTO;

import java.nio.file.Files;
import java.nio.file.Path;

public class TestSenderApp {

    public static void main(String[] args) throws Exception {
        // args: <url> <sespct-public-pem-file> <ct-private-pem-file>
        if (args.length < 3) {
            System.err.println("Usage: java -jar app.jar <url> <sespct-public.pem> <ct-private.pem>");
            System.exit(2);
        }
        String url = args[0];
        Path sespctPubFile = Path.of(args[1]);
        Path ctPrivFile   = Path.of(args[2]);

        String sespctApiPublicPem = Files.readString(sespctPubFile);
        String ctPrivatePem       = Files.readString(ctPrivFile);

        // start Micronaut context so we can reuse CtCompactCrypto, HttpClient beans, etc.
        ApplicationContext ctx = ApplicationContext.run();
        try {
            TestSender sender = ctx.getBean(TestSender.class);
            sender.send(url, sespctApiPublicPem, ctPrivatePem);
        } finally {
            ctx.close();
        }
    }
}
