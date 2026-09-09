package com.vaibhav.weave;
import org.junit.jupiter.api.Test;
import com.vaibhav.weave.crdt.CoreTest;
class CoreRegressionTest {
    @Test void phaseOneCoreRemainsCorrect(){CoreTest.main(new String[0]);}
}
