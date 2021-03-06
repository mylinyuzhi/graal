/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.posix;

import java.io.FileDescriptor;

import org.graalvm.nativeimage.Feature;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.LogHandler;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.posix.headers.LibC;

@AutomaticFeature
class PosixLogHandlerFeature implements Feature {
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        /* An alternative log handler can be set in Feature.duringSetup(). */
        if (!ImageSingletons.contains(LogHandler.class)) {
            /*
             * Install the default log handler in ImageSingletons such that if another feature tries
             * to install another log handler at a later point it will get an error.
             */
            LogHandler logHandler = new PosixLogHandler();
            ImageSingletons.add(LogHandler.class, logHandler);
        }
    }
}

public class PosixLogHandler implements LogHandler {

    @Override
    public void log(CCharPointer bytes, UnsignedWord length) {
        if (!PosixUtils.writeBytes(getOutputFile(), bytes, length)) {
            /*
             * We are in a low-level log routine and output failed, so there is little we can do.
             */
            fatalError();
        }
    }

    @Override
    public void flush() {
        PosixUtils.flush(getOutputFile());
        /* ignore error -- they're benign */
    }

    @Override
    public void fatalError() {
        LibC.abort();
    }

    private static FileDescriptor getOutputFile() {
        return FileDescriptor.err;
    }
}
