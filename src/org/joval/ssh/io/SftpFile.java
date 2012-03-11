// Copyright (C) 2011 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.ssh.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Vector;

import org.vngx.jsch.ChannelSftp;
import org.vngx.jsch.SftpATTRS;
import org.vngx.jsch.exception.SftpException;

import org.joval.intf.io.IRandomAccess;
import org.joval.intf.ssh.ISftpError;
import org.joval.intf.unix.io.IUnixFileInfo;
import org.joval.io.fs.CacheFile;
import org.joval.io.fs.FileAccessor;
import org.joval.io.fs.FileInfo;
import org.joval.util.JOVALMsg;
import org.joval.util.JOVALSystem;

/**
 * An IFile wrapper for an SFTP channel.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
class SftpFile extends CacheFile {
    SftpFile(SftpFilesystem fs, String path) {
	super(fs, path);
	accessor = new SftpAccessor(fs);
    }

    // Implement abstract methods from CacheFile

    public FileAccessor getAccessor() {
	if (accessor == null) {
	    accessor = new SftpAccessor((SftpFilesystem)fs);
	}
	return accessor;
    }

    // Private

    private class SftpAccessor extends FileAccessor {
	private SftpFilesystem sfs;
	private SftpATTRS attrs = null;
	private String permissions = null;

	SftpAccessor(SftpFilesystem sfs) {
	    this.sfs = sfs;
	}

	public boolean exists() {
	    if (attrs != null) {
		return true;
	    }
	    try {
		if (isRoot()) {
		    attrs = sfs.getCS().lstat(sfs.getDelimiter());
		} else {
		    attrs = sfs.getCS().lstat(path);
		}
		permissions = attrs.getPermissionsString();
		if (permissions.length() != 10) {
		    throw new IOException("\"" + permissions + "\"");
		}
		return true;
	    } catch (SftpException e) {
		switch(SftpFilesystem.getErrorCode(e)) {
		  case ISftpError.PERMISSION_DENIED:
		    fs.getLogger().warn(JOVALMsg.ERROR_IO, path, "permission denied");
		    return false;

		  case ISftpError.INVALID_FILENAME:
		    fs.getLogger().warn(JOVALMsg.ERROR_IO, path, "invalid filename");
		    return false;

		  case ISftpError.NO_SUCH_PATH:
		  case ISftpError.NO_SUCH_FILE:
		    return false;

		  default:
		    fs.getLogger().warn(JOVALMsg.ERROR_IO, path, e.getMessage());
		    fs.getLogger().debug(JOVALSystem.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
		    break;
		}
	    } catch (IOException e) {
		fs.getLogger().warn(JOVALMsg.ERROR_IO, path, "exists");
		fs.getLogger().warn(JOVALSystem.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	    }
	    return false;
	}

	public FileInfo getInfo() throws IOException {
	    if (exists()) {
		try {
		    return new SftpFileInfo(attrs, permissions, path, sfs.getCS());
		} catch (SftpException e) {
		    throw new IOException(e);
		}
	    } else {
		throw new FileNotFoundException(path);
	    }
	}

	public long getCtime() throws IOException {
	    if (exists()) {
		return FileInfo.UNKNOWN_TIME;
	    } else {
		throw new FileNotFoundException(path);
	    }
	}

	public long getMtime() throws IOException {
	    if (exists()) {
		return attrs.getModifiedTime() * 1000L;
	    } else {
		throw new FileNotFoundException(path);
	    }
	}

	public long getAtime() throws IOException {
	    if (exists()) {
		return attrs.getAccessTime() * 1000L;
	    } else {
		throw new FileNotFoundException(path);
	    }
	}

	public boolean mkdir() {
	    try {
		if (exists()) {
		    return false;
		} else {
		    sfs.getCS().mkdir(path + fs.getDelimiter());
		    return exists();
		}
	    } catch (SftpException e) {
		fs.getLogger().warn(JOVALMsg.ERROR_IO, path, "mkdir");
		fs.getLogger().error(JOVALSystem.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
		return false;
	    } catch (IOException e) {
		fs.getLogger().warn(JOVALMsg.ERROR_IO, path, "mkdir");
		fs.getLogger().error(JOVALSystem.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
		return false;
	    }
	}

	public InputStream getInputStream() throws IOException {
	    if (exists()) {
		try {
		    return sfs.getCS().get(path);
		} catch (SftpException e) {
		    throw new IOException(e);
		}
	    } else {
		throw new FileNotFoundException(path);
	    }
	}

	public OutputStream getOutputStream(boolean append) throws IOException {
	    if (isLink()) {
		return fs.getFile(getCanonicalPath()).getOutputStream(append);
	    } else if (exists() && isDirectory()) {
		String s = JOVALSystem.getMessage(JOVALMsg.ERROR_IO, path, JOVALSystem.getMessage(JOVALMsg.ERROR_IO_NOT_FILE));
		throw new IOException(s);
	    } else {
		int mode = ChannelSftp.OVERWRITE;
		if (append) {
		    mode = ChannelSftp.APPEND;
		}
		try {
		    return sfs.getCS().put(path, mode);
		} catch (SftpException e) {
		    throw new IOException(e);
		}
	    }
	}

	public IRandomAccess getRandomAccess(String mode) throws IllegalArgumentException, IOException {
	    throw new UnsupportedOperationException("Not implemented");
	}

	public boolean isDirectory() throws IOException {
	    if (isLink()) {
		return fs.getFile(getCanonicalPath()).isDirectory();
	    } else if (exists()) {
		return attrs.isDir();
	    } else {
		throw new FileNotFoundException(path);
	    }
	}

	public boolean isFile() throws IOException {
	    if (isLink()) {
		return fs.getFile(getCanonicalPath()).isFile();
	    } else if (exists()) {
		return !isDirectory();
	    } else {
		return true;
	    }
	}

	public long getLength() throws IOException {
	    if (exists()) {
		return attrs.getSize();
	    } else {
		throw new FileNotFoundException(path);
	    }
	}

	public void delete() throws IOException {
	    if (exists()) {
		try {
		    sfs.getCS().rm(path);
		} catch (SftpException e) {
		    throw new IOException(e);
		}
	    } else {
		throw new FileNotFoundException(path);
	    }
	}

	public String getCanonicalPath() throws IOException {
	    try {
		return sfs.getCS().realpath(path);
	    } catch (SftpException e) {
		throw new IOException(e);
	    }
	}

	public String[] list() throws IOException {
	    if (isLink()) {
		return fs.getFile(getCanonicalPath()).list();
	    } else if (isDirectory()) {
		try {
		    Collection<String> list = new Vector<String>();
		    for (ChannelSftp.LsEntry entry : sfs.getCS().ls(path)) {
			if (!".".equals(entry.getFilename()) && !"..".equals(entry.getFilename())) {
		    	list.add(entry.getFilename());
			}
		    }
		    return list.toArray(new String[list.size()]);
		} catch (SftpException se) {
		    switch(SftpFilesystem.getErrorCode(se)) {
		      case ISftpError.NO_SUCH_FILE:
			throw new FileNotFoundException(path);
    
		      default:
			throw new IOException(se);
		    }
		}
	    } else {
		return null;
	    }
	}


    }
}
