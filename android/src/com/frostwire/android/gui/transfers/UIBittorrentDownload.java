/*
 * Created by Angel Leon (@gubatron), Alden Torres (aldenml)
 * Copyright (c) 2011-2015, FrostWire(R). All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.frostwire.android.gui.transfers;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.MediaStore;
import com.frostwire.android.core.ConfigurationManager;
import com.frostwire.android.core.Constants;
import com.frostwire.android.core.FileDescriptor;
import com.frostwire.android.core.providers.TableFetcher;
import com.frostwire.android.core.providers.TableFetchers;
import com.frostwire.android.gui.Librarian;
import com.frostwire.android.gui.NetworkManager;
import com.frostwire.android.gui.services.Engine;
import com.frostwire.bittorrent.BTDownload;
import com.frostwire.bittorrent.BTDownloadItem;
import com.frostwire.bittorrent.BTDownloadListener;
import com.frostwire.bittorrent.PaymentOptions;
import com.frostwire.util.Logger;
import com.frostwire.platform.Platforms;
import com.frostwire.transfers.BittorrentDownload;
import com.frostwire.transfers.TransferItem;
import com.frostwire.transfers.TransferState;
import com.frostwire.util.Ref;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * @author gubatron
 * @author aldenml
 */
public final class UIBittorrentDownload implements BittorrentDownload {

    private static final Logger LOG = Logger.getLogger(UIBittorrentDownload.class);

    private final TransferManager manager;
    private final BTDownload dl;

    private String displayName;
    private long size;
    private List<TransferItem> items;

    private boolean noSpaceAvailableInCurrentMount;

    public UIBittorrentDownload(TransferManager manager, BTDownload dl) {
        this.manager = manager;
        this.dl = dl;
        this.dl.setListener(new StatusListener());

        this.displayName = dl.getDisplayName();
        this.size = calculateSize(dl);
        this.items = calculateItems(dl);

        if (!dl.wasPaused()) {
            dl.resume();
        }

        try {
            noSpaceAvailableInCurrentMount = TransferManager.getCurrentMountAvailableBytes() < size;
        } catch (Throwable t) {

        }
    }

    public BTDownload getDl() {
        return dl;
    }

    @Override
    public String magnetUri() {
        return dl.magnetUri();
    }

    @Override
    public String getInfoHash() {
        return dl.getInfoHash();
    }

    @Override
    public int getConnectedPeers() {
        return dl.getConnectedPeers();
    }

    @Override
    public int getTotalPeers() {
        return dl.getTotalPeers();
    }

    @Override
    public int getConnectedSeeds() {
        return dl.getConnectedSeeds();
    }

    @Override
    public int getTotalSeeds() {
        return dl.getTotalSeeds();
    }

    @Override
    public boolean isSeeding() {
        return dl.isSeeding();
    }

    @Override
    public boolean isPaused() {
        return dl.isPaused();
    }

    @Override
    public boolean isFinished() {
        return dl.isFinished();
    }

    public boolean hasPaymentOptions() {
        return this.dl.getPaymentOptions() != null && !this.dl.getPaymentOptions().isEmpty();
    }

    public PaymentOptions getPaymentOptions() {
        return this.dl.getPaymentOptions();
    }

    @Override
    public void pause() {
        dl.pause();
    }

    @Override
    public void resume() {
        dl.resume();
    }

    @Override
    public File getSavePath() {
        return dl.getSavePath();
    }

    @Override
    public File getContentSavePath() {
        return dl.getContentSavePath();
    }

    @Override
    public boolean isDownloading() {
        return dl.getDownloadSpeed() > 0;
    }

    @Override
    public void remove(boolean deleteData) {
        remove(null, false, deleteData);
    }

    @Override
    public void remove(boolean deleteTorrent, boolean deleteData) {
        remove(null, deleteTorrent, deleteData);
    }

    public void remove(WeakReference<Context> contextRef, boolean deleteTorrent, boolean deleteData) {
        manager.remove(this);

        if (contextRef != null && Ref.alive(contextRef) && deleteData && isComplete()) {
            // Let's remove all the file descriptors from the fetchers
            deleteFilesFromContentResolver(contextRef.get());
        }

        dl.remove(deleteTorrent, deleteData);
    }

    private void deleteFilesFromContentResolver(Context context) {
        final List<TransferItem> items = getItems();
        final ContentResolver cr = context.getContentResolver();
        for (TransferItem item : items) {
            final List<FileDescriptor> fileDescriptors = Librarian.instance().getFiles(item.getFile().getAbsolutePath(), true);
            for (FileDescriptor fd : fileDescriptors) {
                File file = new File(fd.filePath);
                if (file.isFile()) {
                    try {
                        TableFetcher fetcher = TableFetchers.getFetcher(fd.fileType);
                        cr.delete(fetcher.getContentUri(), MediaStore.MediaColumns._ID + " = " + fd.id, null);
                    } catch (Throwable e) {
                        LOG.error("Failed to delete file from media store. (" + fd.filePath + ")", e);
                    }
                }
            }
        }
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public TransferState getState() {
        if (noSpaceAvailableInCurrentMount) {
            return TransferState.ERROR_DISK_FULL;
        }
        return dl.getState();
    }

    @Override
    public int getProgress() {
        try {
            checkSequentialDownload();
        } catch (Throwable e) {
            LOG.error("Error checking sequential download");
        }

        return dl.getProgress();
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public Date getCreated() {
        return dl.getCreated();
    }

    @Override
    public long getBytesReceived() {
        return dl.getBytesReceived();
    }

    @Override
    public long getBytesSent() {
        return dl.getBytesSent();
    }

    @Override
    public long getDownloadSpeed() {
        return dl.getDownloadSpeed();
    }

    @Override
    public long getUploadSpeed() {
        return dl.getUploadSpeed();
    }

    @Override
    public long getETA() {
        return dl.getETA();
    }

    @Override
    public boolean isComplete() {
        return dl.isComplete();
    }

    @Override
    public List<TransferItem> getItems() {
        return items;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public File previewFile() {
        return null;
    }

    void updateUI(BTDownload dl) {
        displayName = dl.getDisplayName();
        size = calculateSize(dl);
        items = calculateItems(dl);
    }

    private class StatusListener implements BTDownloadListener {

        @Override
        public void finished(BTDownload dl) {
            pauseSeedingIfNecessary(dl);
            TransferManager.instance().incrementDownloadsToReview();
            File saveLocation = getSavePath().getAbsoluteFile();
            Engine.instance().notifyDownloadFinished(getDisplayName(), saveLocation, dl.getInfoHash());
            Platforms.fileSystem().scan(saveLocation);
        }

        private void pauseSeedingIfNecessary(BTDownload dl) {
            boolean seedFinishedTorrents = ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_TORRENT_SEED_FINISHED_TORRENTS);
            boolean seedFinishedTorrentsOnWifiOnly = ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_TORRENT_SEED_FINISHED_TORRENTS_WIFI_ONLY);
            boolean isDataWIFIUp = NetworkManager.instance().isDataWIFIUp();
            if (!seedFinishedTorrents || (!isDataWIFIUp && seedFinishedTorrentsOnWifiOnly)) {
                dl.pause();
            }
        }

        @Override
        public void removed(BTDownload dl, Set<File> incompleteFiles) {
            finalCleanup(incompleteFiles);
        }
    }

    private void finalCleanup(Set<File> incompleteFiles) {
        for (File f : incompleteFiles) {
            try {
                if (f.exists() && !f.delete()) {
                    LOG.info("Can't delete file: " + f);
                }
            } catch (Throwable e) {
                LOG.info("Can't delete file: " + f);
            }
        }

        deleteEmptyDirectoryRecursive(dl.getSavePath());
    }

    private static boolean deleteEmptyDirectoryRecursive(File directory) {
        // make sure we only delete canonical children of the parent file we
        // wish to delete. I have a hunch this might be an issue on OSX and
        // Linux under certain circumstances.
        // If anyone can test whether this really happens (possibly related to
        // symlinks), I would much appreciate it.
        String canonicalParent;
        try {
            canonicalParent = directory.getCanonicalPath();
        } catch (IOException ioe) {
            return false;
        }

        if (!directory.isDirectory()) {
            return false;
        }

        boolean canDelete = true;

        File[] files = directory.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                try {
                    if (!files[i].getCanonicalPath().startsWith(canonicalParent))
                        continue;
                } catch (IOException ioe) {
                    canDelete = false;
                }

                if (!deleteEmptyDirectoryRecursive(files[i])) {
                    canDelete = false;
                }
            }
        }

        return canDelete && directory.delete();
    }

    private long calculateSize(BTDownload dl) {
        long size = dl.getSize();

        boolean partial = dl.isPartial();
        if (partial) {
            List<com.frostwire.transfers.TransferItem> items = dl.getItems();

            long totalSize = 0;
            for (com.frostwire.transfers.TransferItem item : items) {
                if (!item.isSkipped()) {
                    totalSize += item.getSize();
                }
            }

            if (totalSize > 0) {
                size = totalSize;
            }
        }

        return size;
    }

    private List<TransferItem> calculateItems(BTDownload dl) {
        List<TransferItem> l = new LinkedList<TransferItem>();

        for (TransferItem item : dl.getItems()) {
            if (!item.isSkipped()) {
                l.add(item);
            }
        }

        return l;
    }

    private void checkSequentialDownload() {
        BTDownloadItem item = getFirstBiggestItem();

        if (item != null) {
            long downloaded = item.getSequentialDownloaded();
            long size = item.getSize();

            if (size > 0) {

                long percent = (100 * downloaded) / size;

                if (percent > 30 || downloaded > 10 * 1024 * 1024) {
                    if (dl.isSequentialDownload()) {
                        dl.setSequentialDownload(false);
                    }
                } else {
                    if (!dl.isSequentialDownload()) {
                        dl.setSequentialDownload(true);
                    }
                }

                //LOG.debug("Seq: " + dl.isSequentialDownload() + " Downloaded: " + downloaded);
            }
        } else {
            if (dl.isSequentialDownload()) {
                dl.setSequentialDownload(false);
            }
        }
    }

    private BTDownloadItem getFirstBiggestItem() {
        BTDownloadItem item = null;

        for (TransferItem it : items) {
            if (it instanceof BTDownloadItem) {
                BTDownloadItem bit = (BTDownloadItem) it;
                if (item == null) {
                    item = bit;
                } else {
                    if (item.getSize() < 2 * 1024 * 1024 && item.getSize() < bit.getSize()) {
                        item = bit;
                    }
                }
            }
        }

        return item;
    }
}
