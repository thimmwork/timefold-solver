// credits for https://www.cs.mun.ca/java-api-1.5/guide/deployment/deployment-guide/upgrade-guide/article-17.html
package ai.timefold.solver.examples.conferencescheduling.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

public class ConnectionFollowRedirects {

    private URLConnection connection;
    private boolean isRedirect;
    private int redirects = 0;

    public ConnectionFollowRedirects(String url) throws IOException {
        this.connection = new URL(url).openConnection();
    }

    public URLConnection getConnection() {
        return connection;
    }

    public int getRedirects() {
        return redirects;
    }

    public InputStream getInputStream() throws IOException {
        InputStream in = null;
        do {
            if (connection instanceof HttpURLConnection lConnection) {
                lConnection.setInstanceFollowRedirects(false);
            }
            // We want to open the input stream before getting headers
            // because getHeaderField() et al swallow IOExceptions.
            in = connection.getInputStream();
            followRedirects();
        } while (isRedirect);
        return in;
    }

    private void followRedirects() throws IOException {
        isRedirect = false;
        if (connection instanceof HttpURLConnection http) {
            int stat = http.getResponseCode();
            if (stat >= 300 && stat <= 307 && stat != 306 &&
                    stat != HttpURLConnection.HTTP_NOT_MODIFIED) {
                redirectConnection(http);
            }
        }
    }

    private void redirectConnection(HttpURLConnection http) throws IOException {
        URL base = http.getURL();
        String location = http.getHeaderField("Location");
        URL target = null;
        if (location != null) {
            target = new URL(base, location);
        }
        http.disconnect();
        // Redirection should be allowed only for HTTP and HTTPS
        // and should be limited to 5 redirections at most.
        if (target == null || !(target.getProtocol().equals("http")
                || target.getProtocol().equals("https"))
                || redirects >= 5) {
            throw new SecurityException("illegal URL redirect");
        }
        isRedirect = true;
        connection = target.openConnection();
        redirects++;
    }
}
