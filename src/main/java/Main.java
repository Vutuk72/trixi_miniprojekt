import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public class Main {

    static final String KOD_OBCE = "573060";
    static final String URL_ZIP =
            "https://www.smartform.cz/download/kopidlno.xml.zip";
    static final Path VYSTUP = Path.of("C:/apps/trixi-miniprojekt/v2 s ai/insert.sql");

    record Obec(String kod, String nazev) {}
    record CastObce(String kod, String nazev, String obecKod) {}

    public static void main(String[] args) throws Exception {
        List<Obec> obce = new ArrayList<>();
        List<CastObce> casti = new ArrayList<>();

        // Zdroj: lokální zip (pokud je zadán jako argument), jinak stažení z ČÚZK
        try (InputStream zdroj = args.length > 0 ? Files.newInputStream(Path.of(args[0])) : stahni(URL_ZIP);
             ZipInputStream zip = new ZipInputStream(zdroj)) {
            najdiXml(zip);
            parsuj(zip, obce, casti);
        }

        System.out.println("Obcí: " + obce.size() + ", částí obce: " + casti.size());

        List<String> sql = Stream.concat(
                obce.stream().map(o -> String.format(
                        "INSERT INTO obec (kod, nazev) VALUES (%s, '%s');",
                        o.kod(), escapuj(o.nazev()))),
                casti.stream().map(c -> String.format(
                        "INSERT INTO casti_obce (kod, nazev, obec_kod) VALUES (%s, '%s', %s);",
                        c.kod(), escapuj(c.nazev()), c.obecKod())))
                .toList();

        Files.createDirectories(VYSTUP.getParent());
        Files.write(VYSTUP, sql);
        System.out.println("SQL uloženo do " + VYSTUP);
    }

    /** Otevře stahování přes HTTPS jako proud dat; nic se neukládá na disk. */
    static InputStream stahni(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Stažení selhalo, HTTP " + response.statusCode() + ": " + url);
        }
        return response.body();
    }

    /** Posune zip na první XML soubor, takže z něj jde rovnou číst. */
    static void najdiXml(ZipInputStream zip) throws IOException {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".xml")) {
                return;
            }
        }
        throw new IOException("V zipu není žádný XML soubor");
    }

    /**
     * Projde XML jako proud událostí (StAX). Celý dokument se nikdy nenačte do paměti;
     * pamatujeme si jen cestu k aktuálnímu elementu a hodnoty rozpracovaného záznamu.
     */
    static void parsuj(InputStream xml, List<Obec> obce, List<CastObce> casti) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);                     // ochrana proti XXE
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader r = factory.createXMLStreamReader(xml);

        Deque<String> cesta = new ArrayDeque<>();   // např. [vf:CastObce, coi:Obec, obi:Kod]
        StringBuilder text = new StringBuilder();
        String kod = null, nazev = null, obecKod = null;

        while (r.hasNext()) {
            switch (r.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    cesta.addLast(jmeno(r));
                    text.setLength(0);
                }
                case XMLStreamConstants.CHARACTERS -> text.append(r.getText());
                case XMLStreamConstants.END_ELEMENT -> {
                    String hodnota = text.toString().trim();
                    switch (konec(cesta)) {
                        case "vf:Obec/obi:Kod", "vf:CastObce/coi:Kod" -> kod = hodnota;
                        case "vf:Obec/obi:Nazev", "vf:CastObce/coi:Nazev" -> nazev = hodnota;
                        case "vf:CastObce/coi:Obec/obi:Kod" -> obecKod = hodnota;
                        default -> { }
                    }
                    String element = cesta.removeLast();
                    if (element.equals("vf:Obec")) {
                        if (KOD_OBCE.equals(kod)) obce.add(new Obec(kod, nazev));
                        kod = nazev = obecKod = null;
                    } else if (element.equals("vf:CastObce")) {
                        if (KOD_OBCE.equals(obecKod)) casti.add(new CastObce(kod, nazev, obecKod));
                        kod = nazev = obecKod = null;
                    }
                    text.setLength(0);
                }
                default -> { }
            }
        }
        r.close();
    }

    /** Vrátí jméno elementu i s prefixem, např. "obi:Kod". */
    static String jmeno(XMLStreamReader r) {
        String prefix = r.getPrefix();
        return (prefix == null || prefix.isEmpty()) ? r.getLocalName() : prefix + ":" + r.getLocalName();
    }

    /**
     * Vrátí konec cesty od posledního vf:Obec / vf:CastObce, např. "vf:CastObce/coi:Obec/obi:Kod".
     * Díky tomu se nesplete obi:Kod obce s kódem vnořeným hlouběji (třeba kódem okresu).
     */
    static String konec(Deque<String> cesta) {
        List<String> casti = new ArrayList<>(cesta);
        for (int i = casti.size() - 1; i >= 0; i--) {
            String s = casti.get(i);
            if (s.equals("vf:Obec") || s.equals("vf:CastObce")) {
                return String.join("/", casti.subList(i, casti.size()));
            }
        }
        return "";
    }

    static String escapuj(String s) {
        return s.replace("'", "''");
    }
}
