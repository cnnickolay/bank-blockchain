package com.digitalasset.quickstart.iou;

import com.daml.ledger.rxjava.DamlLedgerClient;

import java.util.Optional;

public class Main {
    public static void main(String[] args) {
        DamlLedgerClient client = DamlLedgerClient.forHostWithLedgerIdDiscovery("localhost", 9090, Optional.empty());
    }
}
