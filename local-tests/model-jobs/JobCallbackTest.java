/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.*;
public class JobCallbackTest {
    public static void main(String[] args) throws Exception {
        var done = new CountDownLatch(1);
        var job = new Job("isolated-callback-test") { protected IStatus run(IProgressMonitor m) { return Status.OK_STATUS; } };
        job.addJobChangeListener(new JobChangeAdapter() { public void done(IJobChangeEvent e) {
            System.out.println("currentJob matches: " + (Job.getJobManager().currentJob() == job));
            System.out.println("find includes current: " + Job.getJobManager().find(job).length);
            done.countDown();
        }});
        job.schedule();
        if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("Callback timeout");
    }
}
