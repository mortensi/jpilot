package com.redis.jpilot.controllers;


import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import redis.clients.jedis.JedisPooled;


@Controller
public class PromptController {
	
	private final JedisPooled jedisPooled;
	
    public PromptController(JedisPooled jedisPooled) {
		this.jedisPooled = jedisPooled;
    }

    
	@GetMapping("/prompt")
	public String prompt(@RequestParam(name="name", required=false, defaultValue="prompt") String name, Model model) {
		model.addAttribute("system", jedisPooled.get("jpilot:prompt:system"));
		model.addAttribute("user", jedisPooled.get("jpilot:prompt:user"));
		return "prompt";
	}
	
    @PostMapping("/prompt/save")
    public String savePrompt(@RequestParam("prompt") String prompt, @RequestParam("type") String type) {
        if ("system".equals(type)) {
        	jedisPooled.set("jpilot:prompt:system", prompt);
        } else if ("user".equals(type)) {
        	jedisPooled.set("jpilot:prompt:user", prompt);
        }

        return "redirect:/prompt";  
    }
    
}
