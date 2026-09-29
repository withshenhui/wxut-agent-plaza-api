package com.wxut.agentplaza.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SsoTicketStoreTest {

    @Test
    void consume_returnsUsername_andIsOneTime() {
        SsoTicketStore store = new SsoTicketStore();
        String ticket = store.create("2009090714");
        assertNotNull(ticket);
        assertEquals("2009090714", store.consume(ticket));
        assertNull(store.consume(ticket), "ticket 必须一次性消费");
    }

    @Test
    void consume_invalidTicket_returnsNull() {
        SsoTicketStore store = new SsoTicketStore();
        assertNull(store.consume("bogus"));
    }

    @Test
    void consume_expiredTicket_returnsNull() {
        SsoTicketStore store = new SsoTicketStore(0); // TTL 0ms，立即过期
        assertNull(store.consume(store.create("someone")));
    }

    @Test
    void create_differentTicketsEachTime() {
        SsoTicketStore store = new SsoTicketStore();
        assertNotEquals(store.create("u"), store.create("u"));
    }
}
