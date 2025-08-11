package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

class EC2CloudCredentialsTest {

    private MockedStatic<DefaultCredentialsProvider> mockDefaultCredentialsProvider;
    private DefaultCredentialsProvider mockProvider;

    @BeforeEach
    void setUp() {
        mockProvider = mock(DefaultCredentialsProvider.class);
        mockDefaultCredentialsProvider = mockStatic(DefaultCredentialsProvider.class);
    }

    @Test
    void testCreateCredentialsProviderWithIPFalseCredentialsNull() {
        boolean useInstanceProfileForCredentials = false;
        // Null credentialsId simulates the case where no credentials are provided.
        String credentialsId = null;

        DefaultCredentialsProvider.Builder mockBuilder = mock(DefaultCredentialsProvider.Builder.class);

        mockDefaultCredentialsProvider.when(DefaultCredentialsProvider::builder).thenReturn(mockBuilder);
        when(mockBuilder.build()).thenReturn(mockProvider);

        AwsCredentialsProvider result =
                EC2Cloud.createCredentialsProvider(useInstanceProfileForCredentials, credentialsId);

        // Assert
        assertNotNull(result, "Credentials provider should not be null");
        assertEquals(mockProvider, result, "Should return DefaultCredentialsProvider instance");

        // Verify that DefaultCredentialsProvider.builder().build() was called.
        mockDefaultCredentialsProvider.verify(DefaultCredentialsProvider::builder, times(1));
        verify(mockBuilder, times(1)).build();
    }

    @Test
    void testCreateCredentialsProviderWithIPFalseCredentialsEmpty() {
        boolean useInstanceProfileForCredentials = false;
        // Empty string.
        String credentialsId = "";

        DefaultCredentialsProvider.Builder mockBuilder = mock(DefaultCredentialsProvider.Builder.class);

        mockDefaultCredentialsProvider.when(DefaultCredentialsProvider::builder).thenReturn(mockBuilder);
        when(mockBuilder.build()).thenReturn(mockProvider);

        AwsCredentialsProvider result =
                EC2Cloud.createCredentialsProvider(useInstanceProfileForCredentials, credentialsId);

        // Assert
        assertNotNull(result, "Credentials provider should not be null");
        assertEquals(mockProvider, result, "Should return DefaultCredentialsProvider instance");

        // Verify that DefaultCredentialsProvider.builder().build() was called.
        mockDefaultCredentialsProvider.verify(DefaultCredentialsProvider::builder, times(1));
        verify(mockBuilder, times(1)).build();
    }

    @Test
    void testCreateCredentialsProviderWithIPFalseCredentialsWhitespace() {
        boolean useInstanceProfileForCredentials = false;
        // Whitespace string as another edge case of blank input.
        String credentialsId = "   ";

        DefaultCredentialsProvider.Builder mockBuilder = mock(DefaultCredentialsProvider.Builder.class);

        mockDefaultCredentialsProvider.when(DefaultCredentialsProvider::builder).thenReturn(mockBuilder);
        when(mockBuilder.build()).thenReturn(mockProvider);

        AwsCredentialsProvider result =
                EC2Cloud.createCredentialsProvider(useInstanceProfileForCredentials, credentialsId);

        // Assert
        assertNotNull(result, "Credentials provider should not be null");
        assertEquals(mockProvider, result, "Should return DefaultCredentialsProvider instance");

        // Verify that DefaultCredentialsProvider.builder().build() was called.
        mockDefaultCredentialsProvider.verify(DefaultCredentialsProvider::builder, times(1));
        verify(mockBuilder, times(1)).build();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (mockDefaultCredentialsProvider != null) {
            mockDefaultCredentialsProvider.close();
        }
    }
}
