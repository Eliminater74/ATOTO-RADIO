package com.syu.ipc;

interface IModuleCallback {
    void update(int moduleCode, in int[] ints, in float[] flts, in String[] strs);
}
