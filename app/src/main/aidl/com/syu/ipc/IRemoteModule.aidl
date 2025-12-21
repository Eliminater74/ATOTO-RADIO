package com.syu.ipc;

import com.syu.ipc.IModuleCallback;

interface IRemoteModule {
    void cmd(int cmdCode, in int[] ints, in float[] flts, in String[] strs);
    void register(in IModuleCallback callback, int updateCode, int update);
    void unregister(in IModuleCallback callback, int updateCode);
}
