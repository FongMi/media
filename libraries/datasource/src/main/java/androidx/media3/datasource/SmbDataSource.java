package androidx.media3.datasource;

import static java.lang.Math.min;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.IOException;
import java.util.EnumSet;

public class SmbDataSource extends BaseDataSource {

  @Nullable
  private Session session;
  @Nullable
  private SMBClient smbClient;
  @Nullable
  private DiskShare diskShare;
  @Nullable
  private Connection connection;
  @Nullable
  private Uri uri;
  @Nullable
  private File smbFile;
  @Nullable
  private String cachedHost;
  @Nullable
  private String cachedShare;
  @Nullable
  private String cachedFilePath;

  private boolean opened;
  private int cachedPort;
  private long fileLength;
  private long readPosition;
  private long bytesRemaining;

  public SmbDataSource() {
    super(true);
  }

  private static AuthenticationContext getAuthentication(Uri uri) {
    String userInfo = uri.getUserInfo();
    if (userInfo == null) {
      return AuthenticationContext.guest();
    }
    String[] parts = userInfo.split(":", 2);
    String username = parts[0];
    char[] password = parts.length > 1 ? parts[1].toCharArray() : new char[0];
    return new AuthenticationContext(username, password, null);
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    uri = dataSpec.uri;
    transferInitializing(dataSpec);
    String host = uri.getHost();
    int port = uri.getPort() != -1 ? uri.getPort() : 445;
    String path = uri.getPath();
    if (path != null && path.startsWith("/")) {
      path = path.substring(1);
    }
    if (path == null || !path.contains("/")) {
      throw new DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
    }
    String[] parts = path.split("/", 2);
    String shareName = parts[0];
    String filePath = parts[1];
    boolean sameFile = host != null && host.equals(cachedHost) && port == cachedPort && shareName.equals(cachedShare)
        && filePath.equals(cachedFilePath);
    if (!sameFile || smbFile == null) {
      closeSmb();
      try {
        smbClient = new SMBClient();
        connection = smbClient.connect(host, port);
        session = connection.authenticate(getAuthentication(uri));
        diskShare = (DiskShare) session.connectShare(shareName);
        smbFile = diskShare.openFile(filePath, EnumSet.of(AccessMask.GENERIC_READ), null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN, null);
        fileLength = smbFile.getFileInformation().getStandardInformation().getEndOfFile();
      } catch (IOException e) {
        closeSmb();
        throw new DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
      }
      cachedHost = host;
      cachedPort = port;
      cachedShare = shareName;
      cachedFilePath = filePath;
    }
    readPosition = dataSpec.position;
    if (dataSpec.length != C.LENGTH_UNSET) {
      bytesRemaining = dataSpec.length;
    } else {
      bytesRemaining = fileLength - dataSpec.position;
    }
    if (bytesRemaining < 0 || dataSpec.position > fileLength) {
      throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
    }
    opened = true;
    transferStarted(dataSpec);
    return bytesRemaining;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) {
      return 0;
    }
    if (bytesRemaining == 0) {
      return C.RESULT_END_OF_INPUT;
    }
    if (smbFile == null) {
      throw new DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
    }
    int bytesToRead = (int) min(bytesRemaining, length);
    int bytesRead;
    bytesRead = smbFile.read(buffer, readPosition, offset, bytesToRead);
    if (bytesRead <= 0) {
      return C.RESULT_END_OF_INPUT;
    }
    readPosition += bytesRead;
    bytesRemaining -= bytesRead;
    bytesTransferred(bytesRead);
    return bytesRead;
  }

  @Nullable
  @Override
  public Uri getUri() {
    return uri;
  }

  @Override
  public void close() throws IOException {
    uri = null;
    if (opened) {
      opened = false;
      transferEnded();
    }
  }

  public void release() {
    closeSmb();
  }

  private void closeSmb() {
    closeSilently(smbFile);
    closeSilently(diskShare);
    closeSilently(session);
    closeSilently(connection);
    closeSilently(smbClient);
    smbFile = null;
    session = null;
    diskShare = null;
    smbClient = null;
    connection = null;
    cachedHost = null;
    cachedShare = null;
    cachedFilePath = null;
  }

  private static void closeSilently(@Nullable AutoCloseable resource) {
    if (resource != null) {
      try {
        resource.close();
      } catch (Exception ignored) {
      }
    }
  }
}