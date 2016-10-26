package hudson.plugins.ec2.ssh;

import hudson.FilePath;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2Computer;
import hudson.slaves.CommandLauncher;
import hudson.util.StreamCopyThread;
import java.io.*;
import java.nio.charset.StandardCharsets;

class SystemSSHClient implements SshClient {
    private String host;
    private int port;
    private int connectTimeout; //btw, we can pass it as timeout in ssh command too
    private String user;
    private File privateKeyFile;
    private EC2Logger logger;

    SystemSSHClient(EC2Logger logger, String host, int port, int connectTimeout,
                           String user, char[] pemPrivateKey) throws IOException, InterruptedException {
        this.host = host;
        this.port = port;
        this.connectTimeout = connectTimeout;
        this.user = user;
        this.logger = logger;
        privateKeyFile = createIdentityKeyFile(pemPrivateKey);
    }

    @Override
    public void connect() throws IOException, InterruptedException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int r = run("hostname", bos);
        logger.info("[native ssh] connect check result= " + r + " output=" + new String(bos.toByteArray()));
        if (r != 0)
            throw new IOException("[native ssh] Failed to connect due to [" +  new String(bos.toByteArray()) + "].");
    }

    @Override
    public void put(byte[] data, String remoteFileName, String remoteTargetDirectory, String mode) throws IOException, InterruptedException {
        File dataFile = File.createTempFile("ec2", "_data");

        dataFile.delete();
        dataFile.mkdirs();

        File fullSrc = new File(dataFile, remoteFileName);

        save(data, fullSrc, 0600); //need to be able to remove these files

        String sshClientLaunchString = String.format("scp -o StrictHostKeyChecking=no -i %s -P %d %s %s@%s:%s",
                privateKeyFile.getAbsolutePath(), port,
                fullSrc.getAbsolutePath(),
                user, host, remoteTargetDirectory);

        logger.info("[native ssh] copy command: " + sshClientLaunchString);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        int r = exec(sshClientLaunchString, bos);

        logger.info("[native ssh] put exit code " + r + ", result: " + new String(bos.toByteArray()));

        if (r != 0)
            throw new IOException("Scp did not succeed. Reason [" + new String(bos.toByteArray()) + "]");
    }

    public void startCommandPipe(String command, EC2Computer computer, TaskListener listener)
            throws IOException, InterruptedException {
        // Obviously the master must have an installed ssh client.
        String sshClientLaunchString = String.format("ssh -o StrictHostKeyChecking=no -i %s %s@%s -p %d %s",
                privateKeyFile.getAbsolutePath(), user, host, port, command);

        logger.info("[native ssh] Running " + sshClientLaunchString);
        CommandLauncher commandLauncher = new CommandLauncher(sshClientLaunchString);
        commandLauncher.launch(computer, listener);
    }

    private int exec(String command, OutputStream output) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(Util.tokenize(command));

        Process proc = pb.start();

        // capture error information from stderr. this will terminate itself
        // when the process is killed.
        new StreamCopyThread("[native ssh] stderr copier for remote cmd",
                proc.getErrorStream(), output).start();

        new StreamCopyThread("[native ssh] stdout copier for remote cmd",
                proc.getInputStream(), output).start();

        return proc.waitFor();
    }

    @Override
    public int run(String command, OutputStream output) throws IOException, InterruptedException {
        String sshClientLaunchString = String.format("ssh -o StrictHostKeyChecking=no -i %s %s@%s -p %d %s",
                privateKeyFile.getAbsolutePath(), user, host, port, command);

        logger.info("[native ssh] Trying " + sshClientLaunchString);

        int r = exec(sshClientLaunchString, output);

        logger.info("[native ssh] exit code " + r);

        return r;
    }

    @Override
    public void close() {
        if (privateKeyFile != null) privateKeyFile.delete();
    }

    private void save(byte[] data, File tempFile, int permissionMask) throws IOException, InterruptedException {
        try(FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(data);
            fos.flush();
        }

        FilePath filePath = new FilePath(tempFile);
        filePath.chmod(permissionMask);
    }

    private File createIdentityKeyFile(char[] privateKey) throws IOException, InterruptedException {
        File tempFile = File.createTempFile("ec2_", ".pem");

        try {
            save(new String(privateKey).getBytes(StandardCharsets.UTF_8), tempFile, 0400); // octal file mask - readonly by owner
        } catch (Exception e) {
            tempFile.delete();
            throw new IOException("[native ssh] Error creating temporary identity key file.", e);
        }
        return tempFile;
    }
}
