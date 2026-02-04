// Async IPC Callback Template
// Copy and rename for each async operation

package com.lxmf.messenger.reticulum;

interface IOperationCallback {
    void onSuccess(String result);
    void onError(String error);
}

// Add to IReticulumService.aidl:
// void performOperation(String param, IOperationCallback callback);
