/*
 *
 * Nextcloud Android client application
 *
 * @author Tobias Kaminsky
 * Copyright (C) 2020 Tobias Kaminsky
 * Copyright (C) 2020 Nextcloud GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.owncloud.android.datamodel;

import android.content.ContentValues;

import com.owncloud.android.AbstractIT;
import com.owncloud.android.db.ProviderMeta;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.resources.files.CreateFolderRemoteOperation;
import com.owncloud.android.lib.resources.files.SearchRemoteOperation;
import com.owncloud.android.lib.resources.files.UploadFileRemoteOperation;
import com.owncloud.android.lib.resources.files.model.RemoteFile;
import com.owncloud.android.operations.RefreshFolderOperation;
import com.owncloud.android.utils.FileStorageUtils;

import junit.framework.TestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.owncloud.android.lib.resources.files.SearchRemoteOperation.SearchType.PHOTO_SEARCH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FileDataStorageManagerTest extends AbstractIT {

    private FileDataStorageManager sut;

    @Before
    public void before() {
        sut = new FileDataStorageManager(account, targetContext.getContentResolver());
    }

    @After
    public void after() {
        super.after();

        sut.deleteAllFiles();
        sut.deleteVirtuals(VirtualFolderType.PHOTOS);
    }

    @Test
    public void simpleTest() {
        assertTrue(sut.getFileByPath("/").fileExists());
        assertNull(sut.getFileByPath("/123123"));
    }

    @Test
    public void testFolderContent() throws IOException {
        assertTrue(new CreateFolderRemoteOperation("/1/1/", true).execute(client).isSuccess());

        assertTrue(new CreateFolderRemoteOperation("/1/2/", true).execute(client).isSuccess());

        assertTrue(new UploadFileRemoteOperation(FileStorageUtils.getSavePath(account.name) + "/chunkedFile.txt",
                                                 "/1/1/chunkedFile.txt",
                                                 "text/plain",
                                                 String.valueOf(System.currentTimeMillis() / 1000))
                       .execute(client).isSuccess());

        assertTrue(new UploadFileRemoteOperation(FileStorageUtils.getSavePath(account.name) + "/chunkedFile.txt",
                                                 "/1/1/chunkedFile2.txt",
                                                 "text/plain",
                                                 String.valueOf(System.currentTimeMillis() / 1000))
                       .execute(client).isSuccess());

        File imageFile = getFile("imageFile.png");
        assertTrue(new UploadFileRemoteOperation(imageFile.getAbsolutePath(),
                                                 "/1/1/imageFile.png",
                                                 "image/png",
                                                 String.valueOf(System.currentTimeMillis() / 1000))
                       .execute(client).isSuccess());

        // sync
        assertNull(sut.getFileByPath("/1/1/"));

        assertTrue(new RefreshFolderOperation(sut.getFileByPath("/"),
                                              System.currentTimeMillis() / 1000,
                                              false,
                                              false,
                                              sut,
                                              account,
                                              targetContext).execute(client).isSuccess());

        assertTrue(new RefreshFolderOperation(sut.getFileByPath("/1/"),
                                              System.currentTimeMillis() / 1000,
                                              false,
                                              false,
                                              sut,
                                              account,
                                              targetContext).execute(client).isSuccess());

        assertTrue(new RefreshFolderOperation(sut.getFileByPath("/1/1/"),
                                              System.currentTimeMillis() / 1000,
                                              false,
                                              false,
                                              sut,
                                              account,
                                              targetContext).execute(client).isSuccess());

        assertEquals(3, sut.getFolderContent(sut.getFileByPath("/1/1/"), false).size());
    }

    /**
     * This test creates an image, does a photo search (now returned image is not yet in file hierarchy), then root
     * folder is refreshed and it is verified that the same image file is used in database
     */
    @Test
    public void testPhotoSearch() throws IOException {
        String remotePath = "/imageFile.png";
        VirtualFolderType virtualType = VirtualFolderType.PHOTOS;

        assertEquals(0, sut.getFolderContent(sut.getFileByPath("/"), false).size());
        assertEquals(1, sut.getAllFiles().size());

        File imageFile = getFile("imageFile.png");
        assertTrue(new UploadFileRemoteOperation(imageFile.getAbsolutePath(),
                                                 remotePath,
                                                 "image/png",
                                                 String.valueOf(System.currentTimeMillis() / 1000))
                       .execute(client).isSuccess());

        assertNull(sut.getFileByPath(remotePath));

        // search
        SearchRemoteOperation searchRemoteOperation = new SearchRemoteOperation("image/%",
                                                                                PHOTO_SEARCH,
                                                                                false);

        RemoteOperationResult searchResult = searchRemoteOperation.execute(client);
        TestCase.assertTrue(searchResult.isSuccess());
        TestCase.assertEquals(1, searchResult.getData().size());

        OCFile ocFile = FileStorageUtils.fillOCFile((RemoteFile) searchResult.getData().get(0));
        sut.saveFile(ocFile);

        List<ContentValues> contentValues = new ArrayList<>();
        ContentValues cv = new ContentValues();
        cv.put(ProviderMeta.ProviderTableMeta.VIRTUAL_TYPE, virtualType.toString());
        cv.put(ProviderMeta.ProviderTableMeta.VIRTUAL_OCFILE_ID, ocFile.getFileId());

        contentValues.add(cv);

        sut.saveVirtuals(virtualType, contentValues);

        assertEquals(remotePath, ocFile.getRemotePath());

        assertEquals(0, sut.getFolderContent(sut.getFileByPath("/"), false).size());

        assertEquals(1, sut.getVirtualFolderContent(virtualType, false).size());
        assertEquals(2, sut.getAllFiles().size());

        // update root
        assertTrue(new RefreshFolderOperation(sut.getFileByPath("/"),
                                              System.currentTimeMillis() / 1000,
                                              false,
                                              false,
                                              sut,
                                              account,
                                              targetContext).execute(client).isSuccess());


        assertEquals(1, sut.getFolderContent(sut.getFileByPath("/"), false).size());
        assertEquals(1, sut.getVirtualFolderContent(virtualType, false).size());
        assertEquals(2, sut.getAllFiles().size());

        assertEquals(sut.getVirtualFolderContent(virtualType, false).get(0),
                     sut.getFolderContent(sut.getFileByPath("/"), false).get(0));
    }

}
