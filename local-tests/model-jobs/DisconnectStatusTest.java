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

import java.lang.reflect.Proxy;
import org.eclipse.core.runtime.IStatus;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.runtime.jobs.DisconnectJob;

public class DisconnectStatusTest {
    static class TestJob extends DisconnectJob {
        TestJob(DBPDataSourceContainer container) { super(container); }
        void execute() { updateDataSource(new VoidProgressMonitor()); }
    }
    public static void main(String[] args) {
        for (boolean disconnected : new boolean[] {true, false}) {
            var container = (DBPDataSourceContainer) Proxy.newProxyInstance(
                DBPDataSourceContainer.class.getClassLoader(), new Class<?>[] {DBPDataSourceContainer.class},
                (p, method, values) -> switch (method.getName()) {
                    case "getName", "getId" -> "isolated-disconnect-test";
                    case "disconnect" -> disconnected;
                    default -> null;
                });
            var job = new TestJob(container);
            job.execute();
            if (job.getConnectStatus().isOK() != disconnected) throw new AssertionError("Wrong disconnect status");
            if (!disconnected && job.getConnectStatus().getSeverity() != IStatus.CANCEL) throw new AssertionError("Not cancelled");
        }
        System.out.println("PASS: successful and cancelled disconnect preserve their actual status (2 cases)");
    }
}

