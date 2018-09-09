package hudson.plugins.ec2.ssh;

import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2Computer;

import java.io.IOException;
import java.io.OutputStream;

interface SshClient {
    void connect() throws IOException, InterruptedException;

    //upload file to server over scp
    void put(byte[] data, String remoteFileName, String remoteTargetDirectory, String mode)
            throws IOException, InterruptedException;

    //runs command on remote system (over ssh) and dumps stderr and stdout to given output stream
    int run(String command, OutputStream output)
            throws IOException, InterruptedException;

    void startCommandPipe(String command, EC2Computer computer, TaskListener listener)
            throws IOException, InterruptedException;

    void close();
}
