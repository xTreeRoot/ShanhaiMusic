package com.xtreeroot.mb.local

import java.io.IOException

internal enum class PortableProblem(val chinese: String, val english: String) {
    FolderMissing("找不到 MB Data，请重新选择数据位置。", "MB Data is missing. Select the backup folder again."),
    FolderConflict("所选位置已有名为 MB Data 的文件，请更换目录。", "A file named MB Data already exists. Choose another folder."),
    DuplicateFiles("备份目录包含重名文件。", "The backup folder contains duplicate file names."),
    Permission("无法访问资料库目录，请重新选择并授权。", "Shanhai cannot access the backup folder. Select it again to grant access."),
    Read("无法读取资料库备份。", "Could not read the library backup."),
    RestoredPrevious("最新备份暂时读不到，已恢复上一份，未改动备份目录。恢复访问后可重新选择该目录。",
        "Restored the previous backup because the latest could not be read. Select the folder again once it is accessible."),
    Write("无法写入资料库备份，请检查可用空间和目录权限。", "Could not write the library backup. Check available space and folder permissions."),
    SourceFile("本地文件不存在或超出备份上限。", "A local file is missing or exceeds the backup size limit."),
    LocalAudioInvalid("本地音频未通过完整性检查。", "A local audio file failed its integrity check."),
    DownloadsChanged("下载列表已变化，请重试。", "Downloads changed. Try again."),
    MissingAsset("备份缺少文件。", "A file is missing from the backup."),
    InvalidAsset("备份文件未通过完整性检查。", "A backup file failed its integrity check."),
    IndexTooLarge("资料库索引超过备份上限。", "The library index exceeds the backup size limit."),
    Unknown("资料库备份或恢复失败。", "Could not back up or restore the library."),
}

internal class PortableFailure(val problem: PortableProblem, val asset: String? = null) : IOException(problem.english)
