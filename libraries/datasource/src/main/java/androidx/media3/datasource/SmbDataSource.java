package androidx.media3.datasource;

import static androidx.media3.common.util.Util.castNonNull;
import static java.lang.Math.min;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;

import androidx.media3.common.PlaybackException;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.auth.AuthenticationContext;

import com.hierynomus.smbj.share.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;

public class SmbDataSource extends BaseDataSource {

  @Nullable
  private InputStream inputStream;
  @Nullable
  private Connection connection;
  @Nullable
  private SMBClient smbClient;
  @Nullable
  private DiskShare diskShare;
  @Nullable
  private Session session;
  @Nullable
  private Uri uri;

  private boolean opened;
  private long bytesRemaining;

  public SmbDataSource() {
    super(true);
  }

  private AuthenticationContext getAuthentication() {
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
    this.uri = dataSpec.uri;
    String host = uri.getHost();
    int port = uri.getPort() != -1 ? uri.getPort() : SMBClient.DEFAULT_PORT;
    String path = uri.getPath();
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    String[] parts = path.split("/", 2);
    smbClient = new SMBClient();
    connection = smbClient.connect(host, port);
    session = connection.authenticate(getAuthentication());
    diskShare = (DiskShare) session.connectShare(parts[0]);
    File file = diskShare.openFile(parts[1], EnumSet.of(AccessMask.GENERIC_READ), null, EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN, null);
    transferInitializing(dataSpec);
    long skipped = (inputStream = file.getInputStream()).skip(dataSpec.position);
    if (skipped < dataSpec.position) {
      throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
    }
    if (dataSpec.length != C.LENGTH_UNSET) {
      bytesRemaining = dataSpec.length;
    } else {
      bytesRemaining = file.getFileInformation().getStandardInformation().getEndOfFile() - dataSpec.position;
    }
    opened = true;
    transferStarted(dataSpec);
    return bytesRemaining;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) {
      return 0;
    } else if (bytesRemaining == 0) {
      return C.RESULT_END_OF_INPUT;
    }
    int bytesToRead = bytesRemaining == C.LENGTH_UNSET ? length : (int) min(bytesRemaining, length);
    int bytesRead = castNonNull(inputStream).read(buffer, offset, bytesToRead);
    if (bytesRead == -1) {
      return C.RESULT_END_OF_INPUT;
    }
    if (bytesRemaining != C.LENGTH_UNSET) {
      bytesRemaining -= bytesRead;
    }
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
    try {
      if (inputStream != null) {
        inputStream.close();
      }
      if (diskShare != null) {
        diskShare.close();
      }
      if (session != null) {
        session.close();
      }
      if (connection != null) {
        connection.close();
      }
      if (smbClient != null) {
        smbClient.close();
      }
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      inputStream = null;
      connection = null;
      diskShare = null;
      smbClient = null;
      session = null;
      if (opened) {
        opened = false;
        transferEnded();
      }
    }
  }
}