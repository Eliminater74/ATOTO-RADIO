package com.syu.ipc;

import com.syu.ipc.IRemoteModule;

interface IRemoteToolkit {
    IRemoteModule getRemoteModule(int moduleCode);
}
