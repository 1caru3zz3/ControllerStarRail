package com.controllerstar.x360bridge;

interface IX360Service {
    String exec(String command);
    void exit();
    void destroy();
}
