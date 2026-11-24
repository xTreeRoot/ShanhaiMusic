import MBFoundation
import Testing

@Test @MainActor func videoSingleTapAlwaysTogglesControlsAndStaleAutoHideCannotUndoInput() {
    let chrome = VideoChromeState()
    let initial = chrome.revision
    chrome.singleTap(); #expect(!chrome.visible)
    chrome.singleTap(); #expect(chrome.visible)
    chrome.autoHide(revision: initial); #expect(chrome.visible)
    chrome.editing(true)
    chrome.autoHide(revision: chrome.revision); #expect(chrome.visible)
    chrome.editing(false)
    chrome.interacted()
    chrome.autoHide(revision: chrome.revision); #expect(!chrome.visible)
    chrome.singleTap(); #expect(chrome.visible)
}

@Test @MainActor func reopeningVideoRevealsControlsAndRejectsThePreviousHideTimer() {
    let chrome = VideoChromeState()
    let previous = chrome.revision
    chrome.autoHide(revision: previous)
    #expect(!chrome.visible)
    chrome.reveal()
    #expect(chrome.visible)
    chrome.autoHide(revision: previous)
    #expect(chrome.visible)
    chrome.autoHide(revision: chrome.revision)
    #expect(!chrome.visible)
}
