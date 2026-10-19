import java.io.*;
import java.net.*;

public class download-wrapper {
    public static void main(String[] args) throws Exception {
        String url = "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar";
        String dest = ".mvn/wrapper/maven-wrapper.jar";
        
        URL downloadUrl = new URL(url);
        ReadableByteChannel rbc = Channels.newChannel(downloadUrl.openStream());
        FileOutputStream fos = new FileOutputStream(dest);
        fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
        fos.close();
        rbc.close();
        
        System.out.println("Downloaded maven-wrapper.jar successfully");
    }
}
